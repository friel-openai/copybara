/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.onboard;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.GeneralOptions;
import com.google.copybara.configgen.ConfigGenHeuristics;
import com.google.copybara.configgen.ConfigGenHeuristics.DestinationExcludePaths;
import com.google.copybara.configgen.ConfigGenHeuristics.GeneratorTransformations;
import com.google.copybara.configgen.ConfigGenHeuristics.Result;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.FuzzyClosestVersionSelector;
import com.google.copybara.git.GitOptions;
import com.google.copybara.git.GitRepository;
import com.google.copybara.git.GitRevision;
import com.google.copybara.onboard.core.CannotProvideException;
import com.google.copybara.onboard.core.Input;
import com.google.copybara.onboard.core.InputProvider;
import com.google.copybara.onboard.core.InputProviderResolver;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * An input provider that uses the origin and destination content information to infer several
 * fields like the origin_files glob.
 */
public class ConfigHeuristicsInputProvider implements InputProvider {

  private static final Glob INCLUDE_EXCLUDE_NOOP =
      Glob.createGlob(ImmutableList.of("**"), ImmutableList.of("**"));

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "OptionalAssignedToNull"})
  private Optional<Result> cached = null;

  private final GitOptions gitOptions;
  private final GeneralOptions generalOptions;
  private final GeneratorOptions generatorOptions;
  private final ImmutableSet<Path> destinationOnlyPaths;
  private final int percentSimilar;
  private final Console console;
  private final DestinationPathProvider destinationPathProvider;

  public ConfigHeuristicsInputProvider(
      GitOptions gitOptions,
      GeneralOptions generalOptions,
      GeneratorOptions generatorOptions,
      ImmutableSet<Path> destinationOnlyPaths,
      int percentSimilar,
      Console console,
      DestinationPathProvider destinationPathProvider) {
    this.gitOptions = gitOptions;
    this.generalOptions = generalOptions;
    this.generatorOptions = generatorOptions;
    this.destinationOnlyPaths = destinationOnlyPaths;
    this.percentSimilar = percentSimilar;
    this.console = console;
    this.destinationPathProvider = destinationPathProvider;
  }

  @Override
  public <T> Optional<T> resolve(Input<T> input, InputProviderResolver db)
      throws InterruptedException, CannotProvideException {
    URL originUrl = db.resolve(Inputs.GIT_ORIGIN_URL);
    String currentVersion = db.resolve(Inputs.CURRENT_VERSION);
    Path destination = destinationPathProvider.resolve(db);
    Optional<Result> result = computeHeuristic(originUrl, currentVersion, destination);
    if (result.isEmpty()) {
      return Optional.empty();
    }
    if (input == Inputs.ORIGIN_GLOB) {
      Glob resultGlob = result.get().getOriginGlob();
      return resultGlob.equals(INCLUDE_EXCLUDE_NOOP)
          ? Optional.empty()
          : Optional.of(Inputs.ORIGIN_GLOB.asValue(resultGlob));
    }
    if (input == Inputs.TRANSFORMATIONS) {
      GeneratorTransformations transformations = result.get().getTransformations();
      return Optional.of(Inputs.TRANSFORMATIONS.asValue(transformations));
    }
    if (input == Inputs.DESTINATION_EXCLUDE_PATHS) {
      DestinationExcludePaths destinationExcludePaths = result.get().getDestinationExcludePaths();
      return Optional.of(Inputs.DESTINATION_EXCLUDE_PATHS.asValue(destinationExcludePaths));
    }
    return Optional.empty();
  }

  @SuppressWarnings("OptionalAssignedToNull")
  protected Optional<Result> computeHeuristic(
      URL originUrl, String currentVersion, Path destination) {
    if (!Files.isDirectory(destination)) {
      return Optional.empty();
    }
    if (cached != null) {
      return cached;
    }

    try {
      // TODO(malcon): Refactor this class to not depend on git. IOW, be able to generate configs
      // for existing sources for non-git repositories. This would also make the testing of
      // this class easier.
      Path origin = generalOptions.getDirFactory().newTempDir("checkout");
      GitRepository repo =
          gitOptions.cachedBareRepoForUrl(originUrl.toString()).withWorkTree(origin);

      FuzzyClosestVersionSelector selector = new FuzzyClosestVersionSelector();
      currentVersion = selector.selectVersion(currentVersion, repo, originUrl.toString(), console);

      console.progressFmt("Fetching '%s' from %s", currentVersion, originUrl.toString());
      GitRevision gitRevision;
      try {
        gitRevision =
            repo.fetchSingleRefWithTags(
                originUrl.toString(),
                currentVersion,
                /* fetchTags= */ true,
                /* partialFetch= */ false,
                Optional.empty());
      } catch (RepoException e) {
        gitRevision =
            repo.fetchSingleRef(
                originUrl.toString(), currentVersion, /* partialFetch= */ false, Optional.empty());
      }
      Path git = Files.createDirectories(origin);
      ImmutableList<String> upstreamTags =
          repo.showRef().keySet().stream()
              .filter(ref -> ref.startsWith("refs/tags/"))
              .collect(toImmutableList());

      console.progressFmt("Checking out git files");
      repo.withWorkTree(git).forceCheckout(gitRevision.getSha1());

      ConfigGenHeuristics heuristics =
          getConfigGenHeuristics(
              destination,
              origin,
              destinationOnlyPaths,
              percentSimilar,
              generatorOptions,
              generalOptions,
              upstreamTags);

      console.progressFmt("Computing globs");
      cached = Optional.of(heuristics.run());
      return cached;

    } catch (ValidationException | IOException | RepoException e) {
      logger.atWarning().withCause(e).log("Cannot compute heuristics for repository %s", originUrl);
      cached = Optional.empty();
      return cached;
    }
  }

  /**
   * Returns a {@link ConfigGenHeuristics} object.
   *
   * @param destination the local path to the destination
   * @param origin the local path to the origin
   * @param destinationOnlyPaths paths that should be considered destination-only, and excluded from
   *     heuristics
   * @param percentSimilar the threshold for considering an origin file similar enough to a
   *     destination file
   * @param generatorOptions the generator options from {@link com.google.copybara.Options}
   * @param generalOptions the general options from {@link com.google.copybara.Options}
   * @param versions a list of version refs from the upstream
   * @return the object
   */
  protected ConfigGenHeuristics getConfigGenHeuristics(
      Path destination,
      Path origin,
      ImmutableSet<Path> destinationOnlyPaths,
      int percentSimilar,
      GeneratorOptions generatorOptions,
      GeneralOptions generalOptions,
      ImmutableList<String> versions) {
    return new ConfigGenHeuristics(
        origin,
        destination,
        destinationOnlyPaths,
        percentSimilar,
        generatorOptions.computeGlobIgnoreCarriageReturn,
        generatorOptions.computeGlobIgnoreWhitespace,
        generalOptions,
        versions);
  }

  @Override
  public ImmutableMap<Input<?>, Integer> provides() throws CannotProvideException {
    return defaultPriority(
        ImmutableSet.of(
            Inputs.ORIGIN_GLOB, Inputs.TRANSFORMATIONS, Inputs.DESTINATION_EXCLUDE_PATHS));
  }

  /**
   * Resolves a destination path for glob generation heuristics. This allows the destination path to
   * be different than the generator output folder, if needed.
   */
  @FunctionalInterface
  public interface DestinationPathProvider {
    Path resolve(InputProviderResolver db) throws InterruptedException, CannotProvideException;
  }
}

// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.pkgcache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.UnixGlob;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A mapping from the name of a package to the location of its BUILD file.
 * The implementation composes an ordered sequence of directories according to
 * the package-path rules.
 *
 * <p>All methods are thread-safe, and (assuming no change to the underlying
 * filesystem) idempotent.
 */
public class PathPackageLocator implements Serializable {

  public static final Set<String> DEFAULT_TOP_LEVEL_EXCLUDES = ImmutableSet.of("experimental");

  private final ImmutableList<Path> pathEntries;
  // Transient because this is an injected value in Skyframe, and as such, its serialized
  // representation is used as a key. We want a change to output base not to invalidate things.
  private final transient Path outputBase;

  private PathPackageLocator(Path outputBase, List<Path> pathEntries) {
    this.outputBase = outputBase;
    this.pathEntries = ImmutableList.copyOf(pathEntries);
  }

  /**
   * Constructs a PathPackageLocator based on the specified list of package root directories.
   */
  @VisibleForTesting
  public PathPackageLocator(List<Path> pathEntries) {
    this(null, pathEntries);
  }

  /**
   * Constructs a PathPackageLocator based on the specified array of package root directories.
   */
  public PathPackageLocator(Path... pathEntries) {
    this(null, Arrays.asList(pathEntries));
  }

  /**
   * Returns the path to the build file for this package.
   *
   * <p>The package's root directory may be computed by calling getParentFile()
   * on the result of this function.
   *
   * <p>Instances of this interface do not attempt to do any caching, nor
   * implement checks for package-boundary crossing logic; the PackageCache
   * does that.
   *
   * <p>If the same package exists beneath multiple package path entries, the
   * first path that matches always wins.
   */
  public Path getPackageBuildFile(PackageIdentifier packageName) throws NoSuchPackageException {
    Path buildFile  = getPackageBuildFileNullable(packageName, UnixGlob.DEFAULT_SYSCALLS_REF);
    if (buildFile == null) {
      throw new BuildFileNotFoundException(packageName, "BUILD file not found on package path");
    }
    return buildFile;
  }

  /**
   * Like #getPackageBuildFile(), but returns null instead of throwing.
   *
   * @param packageName the name of the package.
   * @param cache a filesystem-level cache of stat() calls.
   */
  public Path getPackageBuildFileNullable(PackageIdentifier packageName,
      AtomicReference<? extends UnixGlob.FilesystemCalls> cache)  {
    if (packageName.getRepository().isDefault()) {
      return getFilePath(packageName.getPackageFragment().getRelative("BUILD"), cache);
    } else if (!packageName.getRepository().isDefault()) {
      Verify.verify(outputBase != null, String.format(
          "External package '%s' needs to be loaded but this PathPackageLocator instance does not "
          + "support external packages",   packageName));
      // This works only to some degree, because it relies on the presence of the repository under
      // $OUTPUT_BASE/external, which is created by the appropriate RepositoryValue. This is true
      // for the invocation in GlobCache, but not for the locator.getBuildFileForPackage()
      // invocation in Parser#include().
      Path buildFile = outputBase.getRelative(packageName.getPathFragment()).getRelative("BUILD");
      FileStatus stat = cache.get().statNullable(buildFile, Symlinks.FOLLOW);
      if (stat != null && stat.isFile()) {
        return buildFile;
      } else {
        return null;
      }
    } else {
      return null;
    }
  }


  /**
   * Returns an immutable ordered list of the directories on the package path.
   */
  public ImmutableList<Path> getPathEntries() {
    return pathEntries;
  }

  @Override
  public String toString() {
    return "PathPackageLocator" + pathEntries;
  }

  /**
   * A factory of PathPackageLocators from a list of path elements.  Elements
   * may contain "%workspace%", indicating the workspace.
   *
   * @param outputBase the output base. Can be null if remote repositories are not in use.
   * @param pathElements Each element must be an absolute path, relative path,
   *                     or some string "%workspace%" + relative, where relative is itself a
   *                     relative path.  The special symbol "%workspace%" means to interpret
   *                     the path relative to the nearest enclosing workspace.  Relative
   *                     paths are interpreted relative to the client's working directory,
   *                     which may be below the workspace.
   * @param eventHandler The eventHandler.
   * @param workspace The nearest enclosing package root directory.
   * @param clientWorkingDirectory The client's working directory.
   * @param checkExistence If true, verify that the element exists before adding it to the locator.
   * @return a list of {@link Path}s.
   */
  public static PathPackageLocator create(Path outputBase,
                                          List<String> pathElements,
                                          EventHandler eventHandler,
                                          Path workspace,
                                          Path clientWorkingDirectory,
                                          boolean checkExistence) {
    List<Path> resolvedPaths = new ArrayList<>();
    final String workspaceWildcard = "%workspace%";

    for (String pathElement : pathElements) {
      // Replace "%workspace%" with the path of the enclosing workspace directory.
      pathElement = pathElement.replace(workspaceWildcard, workspace.getPathString());

      PathFragment pathElementFragment = new PathFragment(pathElement);

      // If the path string started with "%workspace%" or "/", it is already absolute,
      // so the following line is a no-op.
      Path rootPath = clientWorkingDirectory.getRelative(pathElementFragment);

      if (!pathElementFragment.isAbsolute() && !clientWorkingDirectory.equals(workspace)) {
        eventHandler.handle(
            Event.warn("The package path element '" + pathElementFragment + "' will be "
                + "taken relative to your working directory. You may have intended "
                + "to have the path taken relative to your workspace directory. "
                + "If so, please use the '" + workspaceWildcard + "' wildcard."));
      }

      if (!checkExistence || rootPath.exists()) {
        resolvedPaths.add(rootPath);
      }
    }
    return new PathPackageLocator(outputBase, resolvedPaths);
  }

    /**
     * A factory of PathPackageLocators from a list of path elements.  Elements
     * may contain "%workspace%", indicating the workspace.
     *
     * @param outputBase the output base. Can be null if remote repositories are not in use.
     * @param pathElements Each element must be an absolute path, relative path,
     *                     or some string "%workspace%" + relative, where relative is itself a
     *                     relative path.  The special symbol "%workspace%" means to interpret
     *                     the path relative to the nearest enclosing workspace.  Relative
     *                     paths are interpreted relative to the client's working directory,
     *                     which may be below the workspace.
     * @param eventHandler The eventHandler.
     * @param workspace The nearest enclosing package root directory.
     * @param clientWorkingDirectory The client's working directory.
     * @return a list of {@link Path}s.
     */
    public static PathPackageLocator create(Path outputBase,
            List<String> pathElements, EventHandler eventHandler, Path workspace,
            Path clientWorkingDirectory) {
        return create(outputBase, pathElements, eventHandler, workspace, clientWorkingDirectory,
                /*checkExistence=*/true);
    }

  /**
   * Returns the path to the WORKSPACE file for this build.
   *
   * <p>If there are WORKSPACE files beneath multiple package path entries, the first one always
   * wins.
   */
  public Path getWorkspaceFile() {
    AtomicReference<? extends UnixGlob.FilesystemCalls> cache = UnixGlob.DEFAULT_SYSCALLS_REF;
    // TODO(bazel-team): correctness in the presence of changes to the location of the WORKSPACE
    // file.
    return getFilePath(new PathFragment("WORKSPACE"), cache);
  }

  private Path getFilePath(PathFragment suffix,
      AtomicReference<? extends UnixGlob.FilesystemCalls> cache) {
    for (Path pathEntry : pathEntries) {
      Path buildFile = pathEntry.getRelative(suffix);
      FileStatus stat = cache.get().statNullable(buildFile, Symlinks.FOLLOW);
      if (stat != null && stat.isFile()) {
        return buildFile;
      }
    }
    return null;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(pathEntries, outputBase);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PathPackageLocator)) {
      return false;
    }
    return this.getPathEntries().equals(((PathPackageLocator) other).getPathEntries())
        && Objects.equal(this.outputBase, ((PathPackageLocator) other).outputBase);
  }
}

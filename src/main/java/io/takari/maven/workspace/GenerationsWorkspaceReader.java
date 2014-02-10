package io.takari.maven.workspace;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.SessionScoped;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;

/**
 * An implementation of a workspace reader that knows how to search the Maven reactor for artifacts.
 * 
 * @author Jason van Zyl
 */
@Named
@SessionScoped
public class GenerationsWorkspaceReader implements WorkspaceReader {
  private static final Collection<String> COMPILE_PHASE_TYPES = Arrays.asList("jar", "ejb-client");

  private Map<String, MavenProject> activeProjectMapByGAV;

  private Map<String, List<MavenProject>> projectsByGA;

  private WorkspaceRepository repository;
  //
  // Allow unresolved artifacts, artifacts without a file, to be resolved in the reactor
  //
  private boolean allowArtifactsWithoutAFileToBeResolvedInTheReactor = true;

  @Inject
  public GenerationsWorkspaceReader(MavenSession session) {

    String forceArtifactResolutionFromReactor = session.getSystemProperties().getProperty("maven.forceArtifactResolutionFromReactor");
    if (forceArtifactResolutionFromReactor != null && forceArtifactResolutionFromReactor.equals("true")) {
      allowArtifactsWithoutAFileToBeResolvedInTheReactor = Boolean.parseBoolean(forceArtifactResolutionFromReactor);
    }

    activeProjectMapByGAV = session.getProjectMap();
    projectsByGA = new HashMap<String, List<MavenProject>>();

    for (MavenProject project : activeProjectMapByGAV.values()) {
      String key = ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId());
      List<MavenProject> projects = projectsByGA.get(key);
      if (projects == null) {
        projects = new ArrayList<MavenProject>(1);
        projectsByGA.put(key, projects);
      }
      projects.add(project);
    }

    repository = new WorkspaceRepository("reactor", new HashSet<String>(activeProjectMapByGAV.keySet()));
  }

  //
  // Public API
  //
  public WorkspaceRepository getRepository() {
    return repository;
  }

  public File findArtifact(Artifact artifact) {
    String projectKey = ArtifactUtils.key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());

    // Find the MavenProject that this artifact being requested belongs to
    MavenProject project = activeProjectMapByGAV.get(projectKey);

    if (project != null) {
      File file = find(project, artifact);
      if (file == null && project != project.getExecutionProject()) {
        file = find(project.getExecutionProject(), artifact);
      }
      return file;
    }
    return null;
  }

  public List<String> findVersions(Artifact artifact) {
    String key = ArtifactUtils.versionlessKey(artifact.getGroupId(), artifact.getArtifactId());

    List<MavenProject> projects = projectsByGA.get(key);
    if (projects == null || projects.isEmpty()) {
      return Collections.emptyList();
    }

    List<String> versions = new ArrayList<String>();

    for (MavenProject project : projects) {
      File artifactFile = find(project, artifact);
      if (artifactFile != null) {
        versions.add(project.getVersion());
      }
    }
    return Collections.unmodifiableList(versions);
  }

  //
  // Implementation
  //
  private File find(MavenProject project, Artifact artifact) {
    File file = null;

    if ("pom".equals(artifact.getExtension())) {
      return project.getFile();
    }

    // project = project where we will find artifact
    Artifact projectArtifact = findMatchingArtifact(project, artifact);

    if (hasArtifactFileFromPackagePhase(projectArtifact)) {
      //
      // We have gone far enough in the lifecycle to produce a JAR, WAR, or other file-based artifact
      //
      if (isTestArtifact(artifact)) {
        //
        // We are looking for a test JAR foo-1.0-test.jar
        //
        file = new File(project.getBuild().getDirectory(), String.format("%s-%s-tests.jar", project.getArtifactId(), project.getVersion()));
      } else {
        //
        // We are looking for an application JAR foo-1.0.jar
        //
        file = projectArtifact.getFile();
      }
    } else if (!hasBeenPackaged(project)) {
      //
      // Here no file has been produced so we fallback to loose class files only if artifacts haven't been packaged yet
      // and only for plain old jars. Not war files, not ear files, not anything else.
      //                    
      String type = artifact.getProperty("type", "");

      if (isTestArtifact(artifact)) {
        if (project.hasLifecyclePhase("test-compile")) {
          file = new File(project.getBuild().getTestOutputDirectory());
        }
      } else if (project.hasLifecyclePhase("compile") && COMPILE_PHASE_TYPES.contains(type)) {
        file = new File(project.getBuild().getOutputDirectory());
      }
      if (file == null && allowArtifactsWithoutAFileToBeResolvedInTheReactor) {
        //
        // There is no elegant way to signal that the Artifact's representation is actually present in the 
        // reactor but that it has no file-based representation during this execution and may, in fact, not
        // require one. The case this accounts for something I am doing with Eclipse project file
        // generation where I can perform dependency resolution, but cannot run any lifecycle phases.
        // I need the in-reactor references to work correctly. The WorkspaceReader interface needs
        // to be changed to account for this. This is not exactly elegant, but it's turned on
        // with a property and should not interfere.
        // 
        file = new File(project.getBuild().getOutputDirectory());
        if (file.exists() == false) {
          file = new File(".");
        }
      }
    }

    //
    // The fall-through indicates that the artifact cannot be found;
    // for instance if package produced nothing or classifier problems.
    //
    return file;
  }

  private boolean hasArtifactFileFromPackagePhase(Artifact projectArtifact) {
    return projectArtifact != null && projectArtifact.getFile() != null && projectArtifact.getFile().exists();
  }

  //
  // Currently this works during the mojo execution after one of these phases has been run
  //
  private boolean hasBeenPackaged(MavenProject project) {
    return project.hasLifecyclePhase("package") || project.hasLifecyclePhase("install") || project.hasLifecyclePhase("deploy");
  }

  /**
   * Tries to resolve the specified artifact from the artifacts of the given project.
   * 
   * @param project The project to try to resolve the artifact from, must not be <code>null</code>.
   * @param requestedArtifact The artifact to resolve, must not be <code>null</code>.
   * @return The matching artifact from the project or <code>null</code> if not found. Note that this
   */
  private Artifact findMatchingArtifact(MavenProject project, Artifact requestedArtifact) {
    String requestedRepositoryConflictId = ArtifactIdUtils.toVersionlessId(requestedArtifact);

    Artifact mainArtifact = RepositoryUtils.toArtifact(project.getArtifact());
    if (requestedRepositoryConflictId.equals(ArtifactIdUtils.toVersionlessId(mainArtifact))) {
      return mainArtifact;
    }

    for (Artifact attachedArtifact : RepositoryUtils.toArtifacts(project.getAttachedArtifacts())) {
      if (attachedArtifactComparison(requestedArtifact, attachedArtifact)) {
        return attachedArtifact;
      }
    }

    return null;
  }

  private boolean attachedArtifactComparison(Artifact requested, Artifact attached) {
    //
    // We are taking as much as we can from the DefaultArtifact.equals(). The requested artifact has no file so
    // we want to remove that from the comparision.      
    //

    return requested.getArtifactId().equals(attached.getArtifactId()) && requested.getGroupId().equals(attached.getGroupId())
    // This is to match normal behaviour with artifacts in the reactor where we are not specifically looking at the version.
    // We should be validating the ranges though.
    //
    //&& requested.getVersion().equals( attached.getVersion() )
        && requested.getExtension().equals(attached.getExtension()) && requested.getClassifier().equals(attached.getClassifier());
  }

  /**
   * Determines whether the specified artifact refers to test classes.
   * 
   * @param artifact The artifact to check, must not be {@code null}.
   * @return {@code true} if the artifact refers to test classes, {@code false} otherwise.
   */
  private static boolean isTestArtifact(Artifact artifact) {
    return ("test-jar".equals(artifact.getProperty("type", ""))) || ("jar".equals(artifact.getExtension()) && "tests".equals(artifact.getClassifier()));
  }

  private Map<String, MavenProject> getProjectMap(Collection<MavenProject> projects) {
    Map<String, MavenProject> index = new LinkedHashMap<String, MavenProject>();
    Map<String, List<File>> collisions = new LinkedHashMap<String, List<File>>();

    for (MavenProject project : projects) {
      String projectId = ArtifactUtils.key(project.getGroupId(), project.getArtifactId(), project.getVersion());

      MavenProject collision = index.get(projectId);

      if (collision == null) {
        index.put(projectId, project);
      } else {
        List<File> pomFiles = collisions.get(projectId);

        if (pomFiles == null) {
          pomFiles = new ArrayList<File>(Arrays.asList(collision.getFile(), project.getFile()));
          collisions.put(projectId, pomFiles);
        } else {
          pomFiles.add(project.getFile());
        }
      }
    }

    /*

    We'll assume there is no collisions
    
    if (!collisions.isEmpty()) {
      throw new DuplicateProjectException("Two or more projects in the reactor" + " have the same identifier, please make sure that <groupId>:<artifactId>:<version>" + " is unique for each project: "
          + collisions, collisions);
    }
    */

    return index;
  }
}

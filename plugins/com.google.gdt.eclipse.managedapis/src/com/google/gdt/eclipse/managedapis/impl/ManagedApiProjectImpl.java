/*******************************************************************************
 * Copyright 2011 Google Inc. All Rights Reserved.
 * 
 *  All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/
package com.google.gdt.eclipse.managedapis.impl;

import com.google.gdt.eclipse.core.JavaUtilities;
import com.google.gdt.eclipse.core.WebAppUtilities;
import com.google.gdt.eclipse.core.jobs.DownloadRunnable;
import com.google.gdt.eclipse.managedapis.EclipseProject;
import com.google.gdt.eclipse.managedapis.ManagedApi;
import com.google.gdt.eclipse.managedapis.ManagedApiConstants;
import com.google.gdt.eclipse.managedapis.ManagedApiLogger;
import com.google.gdt.eclipse.managedapis.ManagedApiPlugin;
import com.google.gdt.eclipse.managedapis.ManagedApiProject;
import com.google.gdt.eclipse.managedapis.ManagedApiProjectObserver;
import com.google.gdt.eclipse.managedapis.directory.ApiDirectory;
import com.google.gdt.eclipse.managedapis.extensiontypes.IManagedApiProjectInitializationCallback;
import com.google.gdt.eclipse.managedapis.platform.ManagedApiContainer;
import com.google.gdt.eclipse.managedapis.platform.ManagedApiProjectProperties;
import com.google.gdt.eclipse.managedapis.platform.UpdateManagedApisOperation;
import com.google.gdt.googleapi.core.ApiDirectoryItem;
import com.google.gdt.googleapi.core.ApiDirectoryListing;
import com.google.gson.Gson;

import org.apache.tools.ant.util.FileUtils;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.operations.IWorkbenchOperationSupport;
import org.osgi.service.prefs.BackingStoreException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides access to GPE-managed APIs installed in the specified project.
 * 
 *  Note: The property copyToTargetDir has a special case. If the project has a
 * managed WAR directory, and the copyToTargetDir value is "*" (default), the
 * effective copy-to-dir is determined dynamically by querying the project.
 */
public class ManagedApiProjectImpl implements ManagedApiProject {
  /**
   * Class used for JSON deserialization and getting revision and language
   * version from descriptor.json.
   */
  public static class ApiRevision {
    // Variable names as in JSON labels so gson can get their values from JSON
    // file.
    private String revision;
    private String language_version;

    public String getLanguage_version() {
      return language_version;
    }

    public String getRevision() {
      return revision;
    }

    public void setLanguage_version(String language_version) {
      this.language_version = language_version;
    }

    public void setRevision(String revision) {
      this.revision = revision;
    }
  }

  private static final String ANDROID_2_CLASSPATH_CONTAINER = "Android 2";
  private static final String ANDROID_3_CLASSPATH_CONTAINER = "Android 3";
  private static final String ANDROID_4_CLASSPATH_CONTAINER = "Android 4";
  private static final String OPEN_BRACE = "{";
  private static final String ANDROID2_ENVIRONMENT = "android2";
  private static final String ANDROID3_ENVIRONMENT = "android3";
  public static final String APPENGINE_ENVIRONMENT = "appengine";
  public static final Gson GSON_CODEC = new Gson();
  public static final String GDATA_FOLDER_NAME = "static";

  public static String getAndroidSdk(IProject androidProject) throws JavaModelException {
    if (androidProject == null) {
      return null;
    }
    IJavaProject androidJavaProject = JavaCore.create(androidProject);
    List<IClasspathEntry> rawClasspathList = new ArrayList<IClasspathEntry>();
    rawClasspathList.addAll(Arrays.asList(androidJavaProject.getRawClasspath()));
    for (IClasspathEntry e : rawClasspathList) {
      if (e.getEntryKind() != IClasspathEntry.CPE_CONTAINER) {
        continue;
      }
      IClasspathContainer c = JavaCore.getClasspathContainer(e.getPath(), androidJavaProject);
      if (c.getDescription().contains(ANDROID_2_CLASSPATH_CONTAINER)) {
        return ANDROID2_ENVIRONMENT;
      } else if (c.getDescription().contains(ANDROID_3_CLASSPATH_CONTAINER)
          || c.getDescription().contains(ANDROID_4_CLASSPATH_CONTAINER)) {
        return ANDROID3_ENVIRONMENT;
      }
    }
    return null;
  }

  /**
   * Provide a simple way to produce a ManagedApiProject from a IJavaProject.
   * Note: the ManagedApiProject lives for the duration of the session even if
   * no ManagedApis are added/remaining in the project.
   * 
   * @param project The source project
   * @return A ManagedApiProject wrapper
   * @throws CoreException
   */
  public static ManagedApiProject getManagedApiProject(
      final IJavaProject project) throws CoreException {
    ManagedApiProjectImpl managedApiProject = null;
    IProject iProject = project.getProject();
    if (iProject.isOpen()) {
      managedApiProject = (ManagedApiProjectImpl) iProject.getSessionProperty(
          ManagedApiPlugin.MANAGED_API_SESSION_KEY_QNAME);
      synchronized (project) {
        if (managedApiProject == null
            || managedApiProject.getProject() != project.getProject()) {
          managedApiProject = new ManagedApiProjectImpl(project);
          project.getProject().setSessionProperty(
              ManagedApiPlugin.MANAGED_API_SESSION_KEY_QNAME,
              managedApiProject);
          if (managedApiProject.hasManagedApis()) {
            managedApiProject.initializeProjectWithManagedApis();
          }
        }
      }
    }
    return managedApiProject;
  }

  private static ManagedApi findApiMatchingName(
      List<ManagedApi> managedApisList, String nameToMatch) {
    for (ManagedApi api : managedApisList) {
      if (nameToMatch.equals(api.getName())) {
        return api;
      }
    }
    return null;
  }

  private static IPath toRelativePath(IFolder source) {
    return source.getFullPath().removeFirstSegments(1);
  }

  private Boolean initialized = false;

  private final EclipseProject eProject;

  /**
   * listener field is protected by synchronization on the methods that modify
   * it.
   */
  private ManagedApiChangeListener listener = null;

  private final
      Set<ManagedApiProjectObserver> managedApiProjectObservers = Collections
        .synchronizedSet(new HashSet<ManagedApiProjectObserver>());

  private ManagedApiProjectImpl(IJavaProject project) {
    eProject = new EclipseJavaProject(project);
  }

  public void addManagedApiProjectState() throws CoreException {
    initializeProjectWithManagedApis();
    // set defaults here
    if (WebAppUtilities.hasManagedWarOut(eProject.getProject())) {
      // It is a web app -- set copyToTargetDir to use default (typically
      // war/WEB-INF/lib).
      setDefaultCopyToTargetDir();
    } else if (getAndroidSdk(eProject.getProject()) != null) {
      IFolder libsFolder = eProject.getProject().getFolder("libs");
      if (!libsFolder.exists()) {
        libsFolder.create(true, true, new NullProgressMonitor());
      }
      setCopyToTargetDir(libsFolder);
    }
  }

  /**
   * When a project includes managed APIs, register listeners to handle
   * ManagedApi events using initializeProjectWithManagedApis(). When a project
   * stops having Managed APIs, this method clears all listeners at once.
   */
  public void clearManagedApiProjectObservers() {
    managedApiProjectObservers.clear();
  }

  /**
   * Create a ManagedApi instance from a string referencing the root of the API
   * relative to the ManagedApi root folder.
   * 
   * This call is threadsafe.
   */
  public ManagedApi createManagedApi(String pathRelativeToManagedApiRoot) {
    IFolder rootFolder = getManagedApiRootFolder();
    if (rootFolder != null) {
      IFolder folder = rootFolder.getFolder(pathRelativeToManagedApiRoot);
      if (representsManagedApiCandidate(folder)) {
        ManagedApi managedApi = ManagedApiImpl.createManagedApi(
            eProject, folder);
        ApiDirectory apiDirectory = ManagedApiPlugin.getDefault()
          .getApiDirectoryFactory().buildApiDirectory();
        updateApi(apiDirectory.getApiDirectoryListing(), managedApi);
        return managedApi;
      }
    }
    return null;
  }

  public ManagedApi findManagedApi(String key) {
    ManagedApi api = null;
    ManagedApi[] apis = getManagedApis();
    if (apis != null) {
      for (ManagedApi ma : apis) {
        if (ma.getRootDirectory().getName().equals(key)) {
          api = ma;
          break;
        }
      }
    }
    return api;
  }

  public IFolder getCopyToTargetDir() throws CoreException {
    String copyToTargetDirPath = ManagedApiProjectProperties.getCopyToTargetDir(
        eProject.getProject());
    if (ManagedApiConstants.DEFAULT_COPY_TO_PATH.equals(copyToTargetDirPath)) {
      return getDefaultCopyToTargetDir();
    } else if (copyToTargetDirPath != null) {
      return eProject.getFolder(copyToTargetDirPath);
    } else {
      return null;
    }
  }

  public IFolder getDefaultCopyToTargetDir() {
    IProject project = getProject();
    if (WebAppUtilities.hasManagedWarOut(project)) {
      return WebAppUtilities.getWebInfLib(project.getProject());
    } else {
      return null;
    }
  }

  public IJavaProject getJavaProject() {
    return eProject.getJavaProject();
  }

  public IFolder getManagedApiRootFolder() {
    String localPath = null;
    if (eProject == null) {
      return null;
    }
    localPath = ManagedApiProjectProperties.getManagedApiRootFolderPath(
        eProject.getProject());
    return eProject.getFolder(localPath);
  }

  public ManagedApi[] getManagedApis() {
    List<ManagedApi> installedApis = new ArrayList<ManagedApi>();
    if (eProject != null) {
      IJavaProject project = eProject.getJavaProject();
      if (project != null) {
        try {
          IClasspathEntry[] rawClasspath = project.getRawClasspath();
          for (IClasspathEntry entry : rawClasspath) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
              if (ManagedApiPlugin.API_CONTAINER_PATH.isPrefixOf(
                  entry.getPath())) {
                IClasspathContainer container = JavaCore.getClasspathContainer(
                    entry.getPath(), project);
                if (container instanceof ManagedApiContainer) {
                  ManagedApiContainer managedApiContainer = (ManagedApiContainer) container;
                  installedApis.add(managedApiContainer.getManagedApi());
                }
              }
            }
          }
        } catch (JavaModelException e) {
          ManagedApiLogger.warn(e, "Error reading classpath");
        }
      }
    }
    return installedApis.toArray(new ManagedApi[installedApis.size()]);
  }

  public String getPathRelativeToManagedApiRoot(
      IPackageFragmentRoot fragmentRoot) {
    if (isPackageFragmentRootInManagedApi(fragmentRoot)) {
      IFolder rootFolder = getManagedApiRootFolder();
      int rootLength = rootFolder != null
          ? rootFolder.getFullPath().segmentCount() : 0;
      return fragmentRoot.getPath().removeFirstSegments(rootLength).segment(0);
    } else {
      return null;
    }
  }

  public IProject getProject() {
    return eProject.getProject();
  }

  public boolean hasCopyToTargetDir() throws CoreException {
    return getCopyToTargetDir() != null;
  }

  public boolean hasManagedApis() {
    return getManagedApis().length > 0;
  }

  /**
   * When a project includes managed APIs, register listeners to handle
   * ManagedApi events. When a project stops having Managed APIs, this type
   * clears all listeners with a call to clearManagedApiProjectObservers().
   */
  public void initializeProjectWithManagedApis() {
    synchronized (initialized) {
      if (!initialized) {
        // listen for api changes to manage contents of the CopyToDirectory
        registerManagedApiProjectObserver(
            new ManagedApiProjectCopyToProvider(this));
        IManagedApiProjectInitializationCallback[] initializationCallbacks = ManagedApiPlugin.findProjectInitializationCallbacks();
        for (IManagedApiProjectInitializationCallback initializationCallback :
            initializationCallbacks) {
          initializationCallback.onInitialization(this);
        }
        startListeningForManagedApiChanges();
        initialized = true;
      }
    }
  }

  public void install(IFolder[] uninstalledManagedApiFolders,
      IProgressMonitor monitor, String operationText)
      throws CoreException, ExecutionException {
    int newApiCount = uninstalledManagedApiFolders.length;
    IWorkbenchOperationSupport operationSupport = PlatformUI.getWorkbench()
      .getOperationSupport();
    List<ManagedApi> managedApisList = JavaUtilities
      .copyToArrayListWithExtraCapacity(getManagedApis(), newApiCount);

    for (IFolder apiFolder : uninstalledManagedApiFolders) {
      // create and add to working list
      ManagedApi managedApi = createManagedApi(apiFolder.getName());
      // check to see if list contains older version
      ManagedApi apiToReplace = findApiMatchingName(
          managedApisList, managedApi.getName());
      if (apiToReplace != null) {
        managedApisList.remove(apiToReplace);
      }
      managedApisList.add(managedApi);
    }

    UpdateManagedApisOperation updateManagedApisOp = new UpdateManagedApisOperation(
        operationText);
    updateManagedApisOp.addContext(operationSupport.getUndoContext());
    updateManagedApisOp.setManagedApiProject(this);
    updateManagedApisOp.setAfterManagedApis(
        managedApisList.toArray(new ManagedApiImpl[managedApisList.size()]));
    operationSupport.getOperationHistory()
        .execute(updateManagedApisOp, monitor, null);
  }

  public boolean isPackageFragmentRootInManagedApi(
      IPackageFragmentRoot fragmentRoot) {
    IFolder rootFolder = getManagedApiRootFolder();

    return rootFolder != null
        && rootFolder.getFullPath().isPrefixOf(fragmentRoot.getPath());
  }

  public boolean isUseDefaultCopyToTargetDir() throws CoreException {
    String copyToTargetDirPath = ManagedApiProjectProperties.getCopyToTargetDir(
        eProject.getProject());
    return ManagedApiConstants.DEFAULT_COPY_TO_PATH.equals(copyToTargetDirPath);
  }

  public void notifyCopyToDirectoryChanged(
      IFolder originalFolder, IFolder newFolder) {
    if (!JavaUtilities.equalsWithNullCheck(originalFolder, newFolder)) {
      ManagedApiProjectObserver[] observers = managedApiProjectObservers
        .toArray(
            new ManagedApiProjectObserver[managedApiProjectObservers.size()]);
      for (ManagedApiProjectObserver observer : observers) {
        observer.changeCopyToDirectory(originalFolder, newFolder);
      }
    }
  }

  public void notifyManagedApisAdded(ManagedApi[] apis) {
    ManagedApiProjectObserver[] observers = managedApiProjectObservers.toArray(
        new ManagedApiProjectObserver[managedApiProjectObservers.size()]);
    for (ManagedApiProjectObserver observer : observers) {
      observer.addManagedApis(apis);
    }
  }

  public void notifyManagedApisRefreshed(ManagedApi[] apis) {
    ManagedApiProjectObserver[] observers = managedApiProjectObservers.toArray(
        new ManagedApiProjectObserver[managedApiProjectObservers.size()]);
    for (ManagedApiProjectObserver observer : observers) {
      observer.refreshManagedApis(apis);
    }
  }

  public void notifyManagedApisRemoved(ManagedApi[] apis) {
    ManagedApiProjectObserver[] observers = managedApiProjectObservers.toArray(
        new ManagedApiProjectObserver[managedApiProjectObservers.size()]);
    for (ManagedApiProjectObserver observer : observers) {
      observer.removeManagedApis(apis);
    }
  }

  public void notifyUninstalled(
      final ManagedApi[] apisRemoved, final IProgressMonitor monitor)
      throws ExecutionException {

    int removedApiCount = apisRemoved.length;
    IWorkbenchOperationSupport operationSupport = PlatformUI.getWorkbench()
      .getOperationSupport();
    List<ManagedApi> managedApisBeforeRemoval = JavaUtilities
      .copyToArrayListWithExtraCapacity(getManagedApis(), removedApiCount);
    managedApisBeforeRemoval.addAll(Arrays.asList(apisRemoved));

    UpdateManagedApisOperation updateManagedApisOp = new UpdateManagedApisOperation(
        "Remove API");
    updateManagedApisOp.addContext(operationSupport.getUndoContext());
    updateManagedApisOp.setManagedApiProject(this);
    updateManagedApisOp.setBeforeManagedApis(managedApisBeforeRemoval.toArray(
        new ManagedApi[managedApisBeforeRemoval.size()]));
    updateManagedApisOp.setAfterManagedApis(getManagedApis());
    operationSupport.getOperationHistory().add(updateManagedApisOp);

    // Because we don't execute the operation do notifications here
    notifyManagedApisRemoved(apisRemoved);
  }

  public void registerManagedApiProjectObserver(
      ManagedApiProjectObserver observer) {
    managedApiProjectObservers.add(observer);
  }

  public void removeManagedApiProjectState() {
    stopListeningForManagedApiChanges();
    removeManagedApiClasspathEntries();
    clearManagedApiProjectObservers();
    if (ManagedApiPlugin.DO_DELETES) {
      deleteRootFolder();
    }
  }

  public void setCopyToTargetDir(IFolder copyToTarget) throws CoreException {
    try {
      String copyClasspathEntriesTargetPath = (copyToTarget == null
          ? null : toRelativePath(copyToTarget).toString());
      IFolder originalFolder = getCopyToTargetDir();
      ManagedApiProjectProperties.setCopyToTargetDirPath(
          eProject.getProject(), copyClasspathEntriesTargetPath);
      notifyCopyToDirectoryChanged(originalFolder, copyToTarget);
    } catch (BackingStoreException e) {
      throw new CoreException(new Status(IStatus.ERROR,
          ManagedApiPlugin.PLUGIN_ID, "Failure to write properties", e));
    }
  }

  public void setDefaultCopyToTargetDir() throws CoreException {
    try {
      IFolder originalFolder = getCopyToTargetDir();
      ManagedApiProjectProperties.setCopyToTargetDirPath(
          eProject.getProject(), ManagedApiConstants.DEFAULT_COPY_TO_PATH);
      IFolder copyToTarget = getCopyToTargetDir();
      notifyCopyToDirectoryChanged(originalFolder, copyToTarget);
    } catch (BackingStoreException e) {
      throw new CoreException(new Status(IStatus.ERROR,
          ManagedApiPlugin.PLUGIN_ID, "Failure to write properties", e));
    }
  }

  public void setManagedApiRootFolder(IFolder managedApiRootFolder)
      throws CoreException {
    try {
      String managedApiRootFolderPath = toRelativePath(managedApiRootFolder)
          .toString();
      ManagedApiProjectProperties.setManagedApiRootFolderPath(
          eProject.getProject(), managedApiRootFolderPath);
    } catch (BackingStoreException e) {
      throw new CoreException(new Status(IStatus.ERROR,
          ManagedApiPlugin.PLUGIN_ID, "Failure to write properties", e));
    }
  }

  /**
   * Update the state of Managed APIs in the current project. This type takes an
   * ApiDirectoryListing (a list of APIs from a directory) and flags APIs as
   * having an update available if the version has changed.
   * 
   * Note: this method is threadsafe.
   */
  public void updateApis(ApiDirectoryListing apiDirectoryListing) {
    for (ManagedApi api : getManagedApis()) {
      updateApi(apiDirectoryListing, api);
    }
  }

  private boolean checkRevisionChange(
      ManagedApi api, ApiDirectoryListing apiDirectoryListing) {
    try {
      URL descriptorDownloadLink = null;
      // Get Descriptor download link from corresponding directory item.
      for (ApiDirectoryItem apiDirectoryItem : apiDirectoryListing.getByName(
          api.getName())) {
        if (apiDirectoryItem.getVersion().equals(api.getVersion())) {
          if (apiDirectoryItem.getDownloadLink()
              .toString().contains(GDATA_FOLDER_NAME)) {
            return false;
          }
          descriptorDownloadLink = new URL(apiDirectoryItem.getDownloadLink()
              + "&descriptor-only=1");
          break;
        }
      }
      if (descriptorDownloadLink == null) {
        // If no corresponding directory item, no new revision possible.
        return false;
      }
      File descriptorFile = File.createTempFile(
          "eclipse-gpe-managed-apis-", ".txt");
      descriptorFile.deleteOnExit();
      new DownloadRunnable(descriptorDownloadLink, descriptorFile).run(
          new NullProgressMonitor());
      String descriptorContent = FileUtils.readFully(
          new FileReader(descriptorFile));
      if (descriptorContent == null
          || descriptorContent.indexOf(OPEN_BRACE) == -1) {
        // If descriptor doesn't have JSON, codegen doesn't have descriptor so
        // quit.
        return false;
      }
      descriptorContent = descriptorContent.substring(
          descriptorContent.indexOf("{"));
      IFile localDescriptorFile = ManagedApiImpl.scanManagedApiFiles(
          eProject, api.getRootDirectory()).getDescriptor();
      if (localDescriptorFile == null) {
        // If no local descriptor, then set as Upgrade required to download
        // again.
        return true;
      }
      String localDescriptorContent = FileUtils.readFully(
          new FileReader(localDescriptorFile.getLocation().toFile()));
      ApiRevision localRevision = GSON_CODEC.fromJson(
          localDescriptorContent, ApiRevision.class);
      ApiRevision revision = GSON_CODEC.fromJson(
          descriptorContent, ApiRevision.class);
      if (revision.getRevision() != null
          && !revision.getRevision().equals(localRevision.getRevision())) {
        return true;
      }
      if (revision.getLanguage_version()
          != null && !revision.getLanguage_version().equals(
          localRevision.getLanguage_version())) {
        return true;
      }
    } catch (MalformedURLException e) {
      ManagedApiLogger.error(e);
    } catch (IOException e) {
      ManagedApiLogger.error(e);
    } catch (CoreException e) {
      ManagedApiLogger.error(e);
    }
    return false;
  }

  private void deleteRootFolder() {
    final IFolder rootFolder = getManagedApiRootFolder();
    if (rootFolder != null && rootFolder.exists()) {
      Job deleteApiRootJob = new Job("Delete root directory for managed apis") {
          @Override
        protected IStatus run(IProgressMonitor monitor) {
          try {
            if (rootFolder.exists()) {
              rootFolder.delete(true, monitor);
            }
          } catch (CoreException e) {
            ManagedApiLogger.warn(e, MessageFormat.format(
                "Unable to delete root managed api directory <{0}>",
                rootFolder.toString()));
          }
          return Status.OK_STATUS;
        }
      };
      deleteApiRootJob.setSystem(true);
      deleteApiRootJob.setRule(rootFolder.getParent());
      deleteApiRootJob.schedule();
    }
  }

  private void removeManagedApiClasspathEntries() {
    Job writeRawClasspathJob = new Job(
        "Remove managed api entries from classpath") {
        @Override
      protected IStatus run(IProgressMonitor monitor) {
        IStatus returnStatus = Status.OK_STATUS;
        IClasspathEntry[] initialRawClasspath;
        try {
          initialRawClasspath = eProject.getJavaProject().getRawClasspath();
          List<IClasspathEntry> rawClasspathList = new ArrayList<
              IClasspathEntry>(initialRawClasspath.length);
          for (IClasspathEntry entry : initialRawClasspath) {
            String basePath = entry.getPath().segment(0);
            if (!(basePath.equals(ManagedApiPlugin.API_CONTAINER_PATH_ID))) {
              rawClasspathList.add(entry);
            }
          }
          IClasspathEntry[] newRawClasspath = rawClasspathList.toArray(
              new IClasspathEntry[rawClasspathList.size()]);
          eProject.setRawClasspath(newRawClasspath, new NullProgressMonitor());
        } catch (JavaModelException e) {
          ManagedApiLogger.error(
              e, "Caught JavaModelException trying to rewrite raw classpath");
          returnStatus = new Status(IStatus.ERROR, ManagedApiPlugin.PLUGIN_ID,
              "Failure while rewriting raw classpath.");
        }
        return returnStatus;
      }
    };
    writeRawClasspathJob.setSystem(true);
    writeRawClasspathJob.setRule(eProject.getProject().getParent());
    writeRawClasspathJob.schedule();
  }

  private boolean representsManagedApiCandidate(IResource resource) {
    return resource.getType() == IResource.FOLDER && resource.exists();
  }

  private void startListeningForManagedApiChanges() {
    synchronized (this) {
      if (listener == null) {
        listener = new ManagedApiChangeListener() {
            @Override
          public void managedApiProjectClosed() {
          }

            @Override
          public void managedApiProjectRemoved() {
            stopListeningForManagedApiChanges();
          }

            @Override
          public void managedApiRemoved(ManagedApiImpl[] removedManagedApis) {
            try {
              notifyUninstalled(removedManagedApis, new NullProgressMonitor());
            } catch (ExecutionException e) {
              ManagedApiLogger.warn(e, "Error removing APIs");
            }
          }
        };
        listener.setManagedApiProject(this);
        JavaCore.addElementChangedListener(listener);
      }
    }
  }

  private void stopListeningForManagedApiChanges() {
    synchronized (this) {
      if (listener != null) {
        JavaCore.removeElementChangedListener(listener);
        listener = null;
      }
    }
  }

  private void updateApi(
      ApiDirectoryListing apiDirectoryListing, ManagedApi api) {
    api.setRevisionUpdateAvailable(false);
    api.unsetUpdateAvailable();
    if (apiDirectoryListing == null) {
      return;
    }
    if (checkRevisionChange(api, apiDirectoryListing)) {
      api.setUpdateAvailable();
      api.setRevisionUpdateAvailable(true);
      return;
    }
    ApiDirectoryItem[] matchingApis = apiDirectoryListing.getByName(
        api.getName());
    for (ApiDirectoryItem matchingApi : matchingApis) {
      if (matchingApi.isPreferred() && !JavaUtilities.equalsWithNullCheck(
          api.getVersion(), matchingApi.getVersion())) {
        api.setUpdateAvailable();
      }
    }
  }

}

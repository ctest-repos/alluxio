/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.file.metasync;

import alluxio.AlluxioURI;
import alluxio.client.WriteType;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AccessControlException;
import alluxio.exception.BlockInfoException;
import alluxio.exception.DirectoryNotEmptyException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.FileSystemMasterCommonPOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.master.file.DefaultFileSystemMaster;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.contexts.SetAttributeContext;
import alluxio.master.file.meta.Inode;
import alluxio.master.file.meta.InodeIterationResult;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.LockedInodePath;
import alluxio.master.file.meta.MountTable;
import alluxio.master.file.meta.UfsSyncUtils;
import alluxio.master.journal.NoopJournalContext;
import alluxio.master.metastore.ReadOnlyInodeStore;
import alluxio.master.metastore.ReadOption;
import alluxio.master.metastore.RecursiveInodeIterator;
import alluxio.master.metastore.SkippableInodeIterator;
import alluxio.resource.CloseableResource;
import alluxio.security.authorization.Mode;
import alluxio.underfs.Fingerprint;
import alluxio.underfs.UfsFileStatus;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.ListOptions;
import alluxio.util.CommonUtils;
import alluxio.util.IteratorUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * The metadata syncer.
 */
public class MetadataSyncer {
  private final DefaultFileSystemMaster mFsMaster;
  private final ReadOnlyInodeStore mInodeStore;
  private final MountTable mMountTable;
  private final InodeTree mInodeTree;

  public static final FileSystemMasterCommonPOptions NO_TTL_OPTION =
      FileSystemMasterCommonPOptions.newBuilder()
          .setTtl(-1)
          .build();

  private final boolean mIgnoreTTL =
      Configuration.getBoolean(PropertyKey.MASTER_METADATA_SYNC_IGNORE_TTL);

  public MetadataSyncer(
      DefaultFileSystemMaster fsMaster, ReadOnlyInodeStore inodeStore,
      MountTable mountTable, InodeTree inodeTree) {
    mFsMaster = fsMaster;
    mInodeStore = inodeStore;
    mMountTable = mountTable;
    mInodeTree = inodeTree;
  }

  /**
   * Performs a metadata sync.
   *
   * @param path the path to sync
   * @param context the metadata sync context
   * @return the metadata sync result
   */
  public SyncResult sync(AlluxioURI path, MetadataSyncContext context)
      throws Exception {
    System.out.println("Syncing...");
    // TODO what if this path doesn't map to a ufs path
    UnderFileSystem ufs = mMountTable.resolve(path).acquireUfsResource().get();

    MountTable.Resolution resolution = mMountTable.resolve(path);

    UfsStatus ufsSyncPathRoot = null;
    Inode inodeSyncRoot = null;
    try {
      ufsSyncPathRoot = ufs.getStatus(resolution.getUri().getPath());
    } catch (FileNotFoundException ignored) {
      // No-op
    }
    // TODO how to handle race condition here
    try (LockedInodePath lockedInodePath = mInodeTree.lockInodePath(
        path, InodeTree.LockPattern.READ, context.getRpcContext().getJournalContext())) {
      inodeSyncRoot = lockedInodePath.getInodeOrNull();
    }
    InodeIterationResult inodeWithName = null;
    if (inodeSyncRoot != null) {
      inodeWithName = new InodeIterationResult(inodeSyncRoot, AlluxioURI.SEPARATOR + inodeSyncRoot.getName());
    }
    System.out.println("-------Syncing root-------------");
    syncOne(context, path, ufsSyncPathRoot, inodeWithName);
    if (ufsSyncPathRoot != null && ufsSyncPathRoot.isFile()) {
      return new SyncResult(false, 0);
    }

    System.out.println("-------Syncing children-------------");
    Iterator<UfsStatus> ufsStatusIterator = ufs.listStatusIterable(
        path.getPath(),
        ListOptions.defaults().setRecursive(context.isRecursive()),
        context.getStartAfter(),
        2
    );

    long syncRootInodeId = mFsMaster.getFileId(path);
    ReadOption.Builder readOptionBuilder = ReadOption.newBuilder();
    readOptionBuilder.setReadFrom(context.getStartAfter());
    try (SkippableInodeIterator inodeIterator = mInodeStore.getSkippableChildrenIterator(
        syncRootInodeId, readOptionBuilder.build(), context.isRecursive())) {
      updateMetadata(path, context, inodeIterator, ufsStatusIterator, context.getStartAfter());
    }
    mInodeTree.setDirectChildrenLoaded(() -> context.getRpcContext().getJournalContext(),
        inodeSyncRoot.asDirectory());
    return new SyncResult(true, 0);
  }

  /**
   * Performs a metadata sync asynchronously and return a job id (?).
   */
  public void syncAsync() {
  }

  // Path loader
  private void loadPaths() {
  }

  // UFS loader
  private void loadMetadataFromUFS() {
  }


  private static class SingleInodeSyncResult {
    boolean mMoveUfs;
    boolean mMoveInode;
    boolean mSkipChildren;

    public SingleInodeSyncResult(boolean mMoveUfs, boolean mMoveInode, boolean mSkipChildren) {
      this.mMoveUfs = mMoveUfs;
      this.mMoveInode = mMoveInode;
      this.mSkipChildren = mSkipChildren;
    }
  }

  private void updateMetadata(
      AlluxioURI syncRootPath,
      MetadataSyncContext context,
      SkippableInodeIterator alluxioInodeIterator,
      Iterator<UfsStatus> ufsStatusIterator,
      @Nullable String startFrom
  ) throws Exception {
    InodeIterationResult currentInode = IteratorUtils.nextOrNull(alluxioInodeIterator);
    UfsStatus currentUfsStatus = IteratorUtils.nextOrNull(ufsStatusIterator);

    // If startFrom is not null, then this means the metadata sync was previously failed,
    // and resumed by the user. Listing with a startAfter may include parent directories
    // of the first listed object.
    // e.g. if startFrom = /a/b/c
    // /a and /a/b might also be included in the iterators.
    // We skip the processing of these as they are supposed to be taken care by
    // the previous sync already.
    if (startFrom != null) {
      while (currentInode != null && currentInode.getName().compareTo(startFrom) <= 0) {
        currentInode = IteratorUtils.nextOrNull(alluxioInodeIterator);
      }

      while (currentUfsStatus != null && currentUfsStatus.getName().compareTo(startFrom) <= 0) {
        currentUfsStatus = IteratorUtils.nextOrNull(ufsStatusIterator);
      }
    }

    // Case a. Alluxio /foo and UFS /bar
    //    1. WRITE_LOCK lock /bar
    //    2. create /bar
    //    3. unlock /bar
    //    4. move UFS pointer
    // Case b. Alluxio /bar and UFS /foo
    //    1. WRITE_LOCK lock /bar
    //    2. delete /bar RECURSIVELY (call fs master)
    //    3. unlock /bar
    //    4. move Alluxio pointer and SKIP the children of /foo
    // Case c. Alluxio /foo and Alluxio /foo
    //    1. compare the fingerprint
    //    2. WRITE_LOCK /foo
    //    3. update the metadata
    //    4. unlock /foo
    //    5. move two pointers
    while (currentInode != null || currentUfsStatus != null) {
      if (currentInode == null) {
        System.out.println("Inode null");
      } else {
        System.out.println("Inode " + currentInode.getName());
      }
      if (currentUfsStatus == null) {
        System.out.println("Ufs null");
      } else {
        System.out.println("Ufs " + currentUfsStatus.getName());
      }

      SingleInodeSyncResult result = syncOne(
          context, syncRootPath, currentUfsStatus, currentInode);
      if (result.mSkipChildren) {
        alluxioInodeIterator.skipChildrenOfTheCurrent();
      }
      if (result.mMoveInode) {
        currentInode = IteratorUtils.nextOrNull(alluxioInodeIterator);
      }
      if (result.mMoveUfs) {
        currentUfsStatus = IteratorUtils.nextOrNull(ufsStatusIterator);
      }
      // TODO set direct children loaded
      // how to implement?
      //
    }
  }

  private SingleInodeSyncResult syncOne(
      MetadataSyncContext context,
      AlluxioURI syncRootPath,
      @Nullable UfsStatus currentUfsStatus,
      @Nullable InodeIterationResult currentInode)
      throws InvalidPathException, FileDoesNotExistException, FileAlreadyExistsException,
      IOException, BlockInfoException, DirectoryNotEmptyException, AccessControlException {
    Optional<Integer> comparisonResult = currentInode != null && currentUfsStatus != null
        ? Optional.of(currentInode.getName().compareTo(currentUfsStatus.getName())) :
        Optional.empty();

    if (currentInode == null || (comparisonResult.isPresent() && comparisonResult.get() > 0)) {
      // comparisonResult is present implies that currentUfsStatus is not null
      assert currentUfsStatus != null;
      try (LockedInodePath lockedInodePath = mInodeTree.lockInodePath(
          syncRootPath.join(currentUfsStatus.getName()), InodeTree.LockPattern.WRITE_EDGE,
          NoopJournalContext.INSTANCE)) {
        if (currentUfsStatus.isDirectory()) {
          createInodeDirectoryMetadata(context, lockedInodePath, currentUfsStatus);
        } else {
          createInodeFileMetadata(context, lockedInodePath, currentUfsStatus, null);
        }
      }
      return new SingleInodeSyncResult(true, false, false);
    } else if (currentUfsStatus == null || comparisonResult.get() < 0) {
      try (LockedInodePath lockedInodePath = mInodeTree.lockInodePath(
          syncRootPath.join(currentInode.getName()), InodeTree.LockPattern.WRITE_EDGE,
          NoopJournalContext.INSTANCE)) {
        deleteFile(context, lockedInodePath);
      }
      return new SingleInodeSyncResult(false, true, true);
    }
    // HDFS also fetches ACL list, which is ignored for now
    MountTable.Resolution resolution = mMountTable.resolve(syncRootPath.join(currentInode.getName()));
    final String ufsType;
    try (CloseableResource<UnderFileSystem> ufs = resolution.acquireUfsResource()) {
      ufsType = ufs.get().getUnderFSType();
    }
    Fingerprint ufsFingerprint = Fingerprint.create(ufsType, currentUfsStatus);
    boolean containsMountPoint = mMountTable.containsMountPoint(
        syncRootPath.join(currentInode.getName()), true);
    UfsSyncUtils.SyncPlan syncPlan =
        UfsSyncUtils.computeSyncPlan(currentInode.getInode(), ufsFingerprint, containsMountPoint);
    if (syncPlan.toUpdateMetaData() || syncPlan.toDelete() || syncPlan.toLoadMetadata()) {
      try (LockedInodePath lockedInodePath = mInodeTree.lockInodePath(
          syncRootPath.join(currentInode.getName()), InodeTree.LockPattern.WRITE_EDGE,
          NoopJournalContext.INSTANCE)) {
        if (syncPlan.toUpdateMetaData()) {
          updateInodeMetadata(context, lockedInodePath, currentUfsStatus, ufsFingerprint);
        } else if (syncPlan.toDelete() && syncPlan.toLoadMetadata()) {
          deleteFile(context, lockedInodePath);
          createInodeFileMetadata(context, lockedInodePath, currentUfsStatus, resolution);
        } else {
          throw new IllegalStateException("We should never reach here.");
        }
      }
    }
    return new SingleInodeSyncResult(true, true, false);
  }

  private void deleteFile(MetadataSyncContext context, LockedInodePath lockedInodePath)
      throws FileDoesNotExistException, DirectoryNotEmptyException, IOException,
      InvalidPathException {
    DeleteContext syncDeleteContext = DeleteContext.mergeFrom(
            DeletePOptions.newBuilder()
                .setRecursive(true)
                .setAlluxioOnly(true)
                .setUnchecked(true))
        .setMetadataLoad(true);
    mFsMaster.deleteInternal(context.getRpcContext(), lockedInodePath, syncDeleteContext, true);
    System.out.println("Deleted file " + lockedInodePath.getUri());
  }

  private void updateInodeMetadata(
      MetadataSyncContext context, LockedInodePath lockedInodePath,
      UfsStatus ufsStatus, Fingerprint fingerprint)
      throws FileDoesNotExistException, AccessControlException, InvalidPathException {
    // UpdateMetadata is used when a file or a directory only had metadata change.
    // It works by calling SetAttributeInternal on the inodePath.
    short mode = ufsStatus.getMode();
    SetAttributePOptions.Builder builder = SetAttributePOptions.newBuilder()
        .setMode(new Mode(mode).toProto());
    if (!ufsStatus.getOwner().equals("")) {
      builder.setOwner(ufsStatus.getOwner());
    }
    if (!ufsStatus.getGroup().equals("")) {
      builder.setOwner(ufsStatus.getGroup());
    }
    SetAttributeContext ctx = SetAttributeContext.mergeFrom(builder)
        .setUfsFingerprint(fingerprint.serialize())
        .setMetadataLoad(true);
    // Why previously clock is used?
    mFsMaster.setAttributeSingleFile(context.getRpcContext(), lockedInodePath, false,
        CommonUtils.getCurrentMs(), ctx);
    System.out.println("Updated file " + lockedInodePath.getUri());
  }

  private void createInodeFileMetadata(
      MetadataSyncContext context, LockedInodePath lockedInodePath,
      UfsStatus ufsStatus, @Nullable MountTable.Resolution resolutionHint
  ) throws InvalidPathException, FileDoesNotExistException, FileAlreadyExistsException,
      BlockInfoException, IOException {
    // TODO add metrics
    MountTable.Resolution resolution = resolutionHint;
    if (resolution == null) {
      resolution = mMountTable.resolve(lockedInodePath.getUri());
    }

    long blockSize = ((UfsFileStatus) ufsStatus).getBlockSize();
    if (blockSize == UfsFileStatus.UNKNOWN_BLOCK_SIZE) {
      // TODO if UFS never returns the block size, this might fail
      // then we should consider falling back to ufs.getBlockSizeByte()
      throw new RuntimeException("Unknown block size");
    }

    // Metadata loaded from UFS has no TTL set.
    CreateFileContext createFileContext = CreateFileContext.defaults();
    createFileContext.getOptions().setBlockSizeBytes(blockSize);
    // Ancestor should be created before
    createFileContext.getOptions().setRecursive(false);
    FileSystemMasterCommonPOptions commonPOptions =
        mIgnoreTTL ? NO_TTL_OPTION : context.getCommonOptions();
    createFileContext.getOptions()
        .setCommonOptions(FileSystemMasterCommonPOptions.newBuilder()
            .setTtl(commonPOptions.getTtl())
            .setTtlAction(commonPOptions.getTtlAction()));
    createFileContext.setWriteType(WriteType.THROUGH); // set as through since already in UFS
    createFileContext.setMetadataLoad(true);
    createFileContext.setOwner(ufsStatus.getOwner());
    createFileContext.setGroup(ufsStatus.getGroup());
    createFileContext.setXAttr(ufsStatus.getXAttr());
    short ufsMode = ufsStatus.getMode();
    Mode mode = new Mode(ufsMode);
    Long ufsLastModified = ufsStatus.getLastModifiedTime();
    // TODO see if this can be optimized
    if (resolution.getShared()) {
      mode.setOtherBits(mode.getOtherBits().or(mode.getOwnerBits()));
    }
    createFileContext.getOptions().setMode(mode.toProto());
    // NO ACL for now
    if (ufsLastModified != null) {
      createFileContext.setOperationTimeMs(ufsLastModified);
    }
    mFsMaster.createCompleteFileInternalForMetadataSync(
        context.getRpcContext(), lockedInodePath, createFileContext, (UfsFileStatus) ufsStatus);
    System.out.println("Created file " + lockedInodePath.getUri());
  }

  private void createInodeDirectoryMetadata(
      MetadataSyncContext context, LockedInodePath lockedInodePath,
      UfsStatus ufsStatus
  ) throws InvalidPathException, FileDoesNotExistException, FileAlreadyExistsException,
      IOException {
    MountTable.Resolution resolution = mMountTable.resolve(lockedInodePath.getUri());
    boolean isMountPoint = mMountTable.isMountPoint(lockedInodePath.getUri());

    CreateDirectoryContext createDirectoryContext = CreateDirectoryContext.defaults();
    createDirectoryContext.getOptions()
        .setRecursive(false)
        .setAllowExists(false)
        .setCommonOptions(FileSystemMasterCommonPOptions.newBuilder()
            .setTtl(context.getCommonOptions().getTtl())
            .setTtlAction(context.getCommonOptions().getTtlAction()));
    createDirectoryContext.setMountPoint(isMountPoint);
    createDirectoryContext.setMetadataLoad(true);
    createDirectoryContext.setWriteType(WriteType.THROUGH);

    String ufsOwner = ufsStatus.getOwner();
    String ufsGroup = ufsStatus.getGroup();
    short ufsMode = ufsStatus.getMode();
    Long lastModifiedTime = ufsStatus.getLastModifiedTime();
    Mode mode = new Mode(ufsMode);
    if (resolution.getShared()) {
      mode.setOtherBits(mode.getOtherBits().or(mode.getOwnerBits()));
    }
    createDirectoryContext.getOptions().setMode(mode.toProto());
    createDirectoryContext
        .setOwner(ufsOwner)
        .setGroup(ufsGroup)
        .setUfsStatus(ufsStatus);
    createDirectoryContext.setXAttr(ufsStatus.getXAttr());

    if (lastModifiedTime != null) {
      createDirectoryContext.setOperationTimeMs(lastModifiedTime);
    }
    mFsMaster.createDirectoryInternal(
        context.getRpcContext(),
        lockedInodePath,
        resolution.getUfsClient(),
        resolution.getUri(),
        createDirectoryContext
    );
    System.out.println("Created directory " + lockedInodePath.getUri());
  }
}
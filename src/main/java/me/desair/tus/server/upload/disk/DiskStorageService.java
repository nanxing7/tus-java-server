package me.desair.tus.server.upload.disk;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import me.desair.tus.server.exception.InvalidUploadOffsetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link UploadStorageService} that implements storage on disk
 */
public class DiskStorageService extends AbstractDiskBasedService implements UploadStorageService {

    private static final Logger log = LoggerFactory.getLogger(DiskStorageService.class);

    private static final String UPLOAD_SUB_DIRECTORY = "uploads";
    private static final String INFO_FILE = "info";
    private static final String DATA_FILE = "data";

    private Long maxUploadSize = null;
    private UploadIdFactory idFactory;

    public DiskStorageService(final UploadIdFactory idFactory, final String storagePath) {
        super(storagePath + File.separator + UPLOAD_SUB_DIRECTORY);
        Validate.notNull(idFactory, "The IdFactory cannot be null");
        this.idFactory = idFactory;
    }

    @Override
    public void setMaxUploadSize(final Long maxUploadSize) {
        this.maxUploadSize = (maxUploadSize != null && maxUploadSize > 0 ? maxUploadSize : 0);
    }

    @Override
    public long getMaxUploadSize() {
        return maxUploadSize == null ? 0 : maxUploadSize;
    }

    @Override
    public UploadInfo getUploadInfo(final String uploadUrl, final String ownerKey) throws IOException {
        UploadInfo uploadInfo = getUploadInfo(idFactory.readUploadId(uploadUrl));
        if(uploadInfo == null || !Objects.equals(uploadInfo.getOwnerKey(), ownerKey)) {
            return null;
        } else {
            return uploadInfo;
        }
    }

    @Override
    public String getUploadURI() {
        return idFactory.getUploadURI();
    }

    @Override
    public UploadInfo create(final UploadInfo info, final String ownerKey) throws IOException {
        UUID id = createNewId();

        createUploadDirectory(id);

        try {
            Path bytesPath = getBytesPath(id);

            //Create an empty file to storage the bytes of this upload
            Files.createFile(bytesPath);

            //Set starting values
            info.setId(id);
            info.setOffset(0L);
            info.setOwnerKey(ownerKey);

            update(info);

            return info;
        } catch (UploadNotFoundException e) {
            //Normally this cannot happen
            log.error("Unable to create UploadInfo because of an upload not found exception", e);
            return null;
        }
    }

    @Override
    public void update(final UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
        Path infoPath = getInfoPath(uploadInfo.getId());
        Utils.writeSerializable(uploadInfo, infoPath);
    }

    @Override
    public UploadInfo append(final UploadInfo info, final InputStream inputStream) throws IOException, TusException {
        if(info != null) {
            Path bytesPath = getBytesPath(info.getId());

            long max = getMaxUploadSize() > 0 ? getMaxUploadSize() : Long.MAX_VALUE;
            long transferred = 0;
            Long offset = info.getOffset();
            long newOffset = offset;

            try(ReadableByteChannel uploadedBytes = Channels.newChannel(inputStream);
                FileChannel file = FileChannel.open(bytesPath, WRITE)) {

                try {
                    //Lock will be released when the channel closes
                    file.lock();

                    //Validate that the given offset is at the end of the file
                    if (!offset.equals(file.size())) {
                        throw new InvalidUploadOffsetException("The upload offset does not correspond to the written bytes. " +
                                "You can only append to the end of an upload");
                    }

                    //write all bytes in the channel up to the configured maximum
                    transferred = file.transferFrom(uploadedBytes, offset, max - offset);
                    newOffset = offset + transferred;

                } catch(Exception ex) {
                    //An error occurred, try to write as much data as possible
                    newOffset = writeAsMuchAsPossible(file);
                    throw ex;
                }

            } finally {
                info.setOffset(newOffset);
                update(info);
            }
        }

        return info;
    }

    @Override
    public void removeLastNumberOfBytes(final UploadInfo info, final long byteCount) throws UploadNotFoundException, IOException {
        if (info != null) {
            Path bytesPath = getBytesPath(info.getId());

            try (FileChannel file = FileChannel.open(bytesPath, WRITE)) {

                //Lock will be released when the channel closes
                file.lock();

                file.truncate(file.size() - byteCount);
                file.force(true);

                info.setOffset(file.size());
                update(info);
            }
        }
    }

    @Override
    public void terminateUpload(final UploadInfo info) throws UploadNotFoundException, IOException {
        if (info != null) {
            Path uploadPath = getPathInStorageDirectory(info.getId());
            FileUtils.deleteDirectory(uploadPath.toFile());
        }
    }

    @Override
    public InputStream getUploadedBytes(final String uploadURI, final String ownerKey) throws IOException, UploadNotFoundException {
        InputStream inputStream = null;

        UUID id = idFactory.readUploadId(uploadURI);
        Path bytesPath = getBytesPath(id);

        //If bytesPath is not null, we know this is a valid Upload URI
        if(bytesPath != null) {
            inputStream = Channels.newInputStream(FileChannel.open(bytesPath, READ));
        }

        return inputStream;
    }

    @Override
    public void cleanupExpiredUploads(final UploadLockingService uploadLockingService) {
        //TODO
    }

    UploadInfo getUploadInfo(final UUID id) throws IOException {
        try {
            Path infoPath = getInfoPath(id);
            return Utils.readSerializable(infoPath, UploadInfo.class);
        } catch (UploadNotFoundException e) {
            return null;
        }
    }

    private Path getBytesPath(final UUID id) throws UploadNotFoundException {
        return getPathInUploadDir(id, DATA_FILE);
    }

    private Path getInfoPath(final UUID id) throws UploadNotFoundException {
        return getPathInUploadDir(id, INFO_FILE);
    }

    private Path createUploadDirectory(final UUID id) throws IOException {
        return Files.createDirectories(getPathInStorageDirectory(id));
    }

    private Path getPathInUploadDir(final UUID id, final String fileName) throws UploadNotFoundException {
        //Get the upload directory
        Path uploadDir = getPathInStorageDirectory(id);
        if(uploadDir != null && Files.exists(uploadDir)) {
            return uploadDir.resolve(fileName);
        } else {
            throw new UploadNotFoundException("The upload for id " + id + " was not found.");
        }
    }

    private synchronized UUID createNewId() throws IOException {
        UUID id;
        do {
            id = idFactory.createId();
            //For extra safety, double check that this ID is not in use yet
        } while(getUploadInfo(id) != null);
        return id;
    }

    private long writeAsMuchAsPossible(final FileChannel file) throws IOException {
        long offset = 0;
        if (file != null) {
            file.force(true);
            offset = file.size();
        }
        return offset;
    }
}
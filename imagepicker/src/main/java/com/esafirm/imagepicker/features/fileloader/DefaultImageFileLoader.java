package com.esafirm.imagepicker.features.fileloader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.esafirm.imagepicker.features.common.ImageLoaderListener;
import com.esafirm.imagepicker.helper.ImagePickerUtils;
import com.esafirm.imagepicker.model.Folder;
import com.esafirm.imagepicker.model.Image;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.Nullable;

public class DefaultImageFileLoader implements ImageFileLoader {

    private Context context;
    private ExecutorService executorService;

    public DefaultImageFileLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    private final String[] projection = new String[]{
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
    };

    @Override
    public void loadDeviceImages(
            final boolean isFolderMode,
            final boolean onlyVideo,
            final boolean includeVideo,
            final boolean includeAnimation,
            final ArrayList<File> excludedImages,
            final ImageLoaderListener listener
    ) {
        getExecutorService().execute(
                new ImageLoadRunnable(
                        isFolderMode,
                        onlyVideo,
                        includeVideo,
                        includeAnimation,
                        excludedImages,
                        listener
                ));
    }

    @Override
    public void abortLoadImages() {
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }

    private ExecutorService getExecutorService() {
        if (executorService == null) {
            executorService = Executors.newSingleThreadExecutor();
        }
        return executorService;
    }

    private class ImageLoadRunnable implements Runnable {

        private boolean isFolderMode;
        private boolean includeVideo;
        private boolean onlyVideo;
        private boolean includeAnimation;
        private ArrayList<File> exlucedImages;
        private ImageLoaderListener listener;

        ImageLoadRunnable(
                boolean isFolderMode,
                boolean onlyVideo,
                boolean includeVideo,
                boolean includeAnimation,
                ArrayList<File> excludedImages,
                ImageLoaderListener listener
        ) {
            this.isFolderMode = isFolderMode;
            this.includeVideo = includeVideo;
            this.includeAnimation = includeAnimation;
            this.onlyVideo = onlyVideo;
            this.exlucedImages = excludedImages;
            this.listener = listener;
        }

        private String getQuerySelection() {
            if (onlyVideo) {
                return MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
            }
            if (includeVideo) {
                return MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + " OR "
                        + MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
            }
            return null;
        }

        private Uri getSourceUri() {
            if (onlyVideo || includeVideo) {
                return MediaStore.Files.getContentUri("external");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            }
        }

        @Override
        public void run() {
            Cursor cursor = context.getContentResolver().query(
                    getSourceUri(),
                    projection,
                    getQuerySelection(),
                    null,
                    MediaStore.Images.Media.DATE_ADDED
            );

            if (cursor == null) {
                listener.onFailed(new NullPointerException());
                return;
            }

            List<Image> temp = new ArrayList<>();
            Map<String, Folder> folderMap = null;
            Map<String, Integer> folderDateMap = null;
            if (isFolderMode) {
                folderMap = new HashMap<>();
                folderDateMap = new HashMap<>();
            }

            if (cursor.moveToLast()) {
                do {
                    String path = cursor.getString(cursor.getColumnIndex(projection[2]));

                    File file = makeSafeFile(path);
                    if (file == null) {
                        continue;
                    }

                    if (exlucedImages != null && exlucedImages.contains(file))
                        continue;

                    // Exclude GIF when we don't want it
                    if (!includeAnimation) {
                        if (ImagePickerUtils.isGifFormat(path)) {
                            continue;
                        }
                    }

                    long id = cursor.getLong(cursor.getColumnIndex(projection[0]));
                    String name = cursor.getString(cursor.getColumnIndex(projection[1]));
                    String bucket = cursor.getString(cursor.getColumnIndex(projection[3]));
                    int dateAdded = cursor.getInt(cursor.getColumnIndex(projection[4]));

                    Image image = new Image(id, name, path);

                    temp.add(image);

                    if (folderMap != null) {
                        Folder folder = folderMap.get(bucket);
                        if (folder == null) {
                            folder = new Folder(bucket);
                            folderMap.put(bucket, folder);
                        }
                        folder.getImages().add(image);
                        int lastDate = folderDateMap.getOrDefault(bucket, 0);
                        if (dateAdded > lastDate) {
                            folderDateMap.put(bucket, dateAdded);
                        }
                    }

                } while (cursor.moveToPrevious());
            }
            cursor.close();

            /* Convert HashMap to ArrayList if not null */
            List<Folder> folders = null;
            if (folderMap != null) {
                ArrayList<Map.Entry<String, Integer>> times =
                    new ArrayList<Map.Entry<String, Integer>>(folderDateMap.entrySet());
                times.sort(new Comparator<Map.Entry<String, Integer>>() {
                  @Override
                  public int compare(Map.Entry<String, Integer> lhs, Map.Entry<String, Integer> rhs) {
                      // -1 - less than, 1 - greater than, 0 - equal, all inversed for descending
                      return lhs.getValue() > rhs.getValue() ? -1 : (lhs.getValue() < rhs.getValue()) ? 1 : 0;
                  }
                });
                folders = new ArrayList<>();
                for (int i = 0; i < times.size(); i++) {
                  folders.add(folderMap.get(times.get(i).getKey()));
                }
            }

            listener.onImageLoaded(temp, folders);
        }
    }

    @Nullable
    private static File makeSafeFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return new File(path);
        } catch (Exception ignored) {
            return null;
        }
    }

}

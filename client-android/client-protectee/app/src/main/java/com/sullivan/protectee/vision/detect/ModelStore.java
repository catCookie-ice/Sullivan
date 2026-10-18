package com.sullivan.protectee.vision.detect;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型文件的存取。
 *
 * <p>查找顺序：用户导入到应用私有目录的模型 → 打包在 assets 里的模型。
 * 这样既能让用户不重新编译就换模型，也保留了"开箱即用"的默认路径。
 */
public final class ModelStore {

    /** 内置模型的文件名，需要手动放到 app/src/main/assets/ 下。 */
    public static final String ASSET_NAME = "yolov8n.onnx";
    /** 可选的类别名文件，每行一个；缺省时按 COCO 80 处理。 */
    public static final String CLASSES_ASSET_NAME = "classes.txt";

    private static final String IMPORTED_FILE_NAME = "model_imported.onnx";
    private static final int BUFFER_SIZE = 1 << 16;

    private ModelStore() {
    }

    @NonNull
    public static File importedFile(@NonNull Context context) {
        return new File(context.getFilesDir(), IMPORTED_FILE_NAME);
    }

    /** 用户是否导入过自定义模型。 */
    public static boolean hasImported(@NonNull Context context) {
        return importedFile(context).isFile();
    }

    /** 是否有模型可用（内置或导入）。 */
    public static boolean hasModel(@NonNull Context context) {
        return hasImported(context) || assetExists(context, ASSET_NAME);
    }

    /**
     * 读取模型字节。
     *
     * @return 模型内容；两处都找不到时返回 {@code null}
     */
    @Nullable
    public static byte[] read(@NonNull Context context) throws IOException {
        File imported = importedFile(context);
        if (imported.isFile()) {
            try (InputStream in = new FileInputStream(imported)) {
                return readFully(in);
            }
        }
        if (assetExists(context, ASSET_NAME)) {
            try (InputStream in = context.getAssets().open(ASSET_NAME)) {
                return readFully(in);
            }
        }
        return null;
    }

    /** 读取可选的类别名文件；不存在或为空时返回 {@code null}。 */
    @Nullable
    public static String[] readClassNames(@NonNull Context context) {
        if (!assetExists(context, CLASSES_ASSET_NAME)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        try (InputStream in = context.getAssets().open(CLASSES_ASSET_NAME);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    /** 把用户选中的文件复制到应用私有目录，覆盖上一次导入的模型。 */
    public static void importFrom(@NonNull Context context, @NonNull Uri uri) throws IOException {
        File target = importedFile(context);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("无法读取所选文件");
            }
            try (OutputStream out = new FileOutputStream(temp)) {
                copy(in, out);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
        // 先写临时文件再改名，避免导入中途失败留下半个模型。
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("无法保存模型文件");
            }
        }
    }

    private static boolean assetExists(@NonNull Context context, @NonNull String name) {
        try (InputStream in = context.getAssets().open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readFully(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}

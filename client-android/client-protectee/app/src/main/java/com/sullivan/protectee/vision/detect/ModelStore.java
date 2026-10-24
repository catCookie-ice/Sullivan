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
 *
 * <p><b>现在是两个模型</b>：检测（{@code yolo26n.onnx}）与深度（{@code yolo26n-depth.onnx}）。
 * 换成 YOLO26 后的分工是 —— 检测给出框，深度给出米数，后者是**独立模型**而非检测的
 * 附带输出（见 {@code DepthEstimator} 类注释）。两个模型各自独立可选：
 * 只有检测模型时，距离退回原来的启发式（{@code ObstacleAdvisor.isNear}）；
 * 两个都在时才启用按米的距离播报。这一点是刻意的 —— 深度模型 20 MB，
 * 不是每台设备都值得装。
 */
public final class ModelStore {

    /** 内置检测模型的文件名，需要手动放到 app/src/main/assets/ 下。 */
    public static final String ASSET_NAME = "yolo26n.onnx";
    /** 内置深度模型的文件名；不存在时距离估算退回启发式。 */
    public static final String DEPTH_ASSET_NAME = "yolo26n-depth.onnx";
    /** 可选的类别名文件，每行一个；缺省时按 COCO 80 处理。 */
    public static final String CLASSES_ASSET_NAME = "classes.txt";

    private static final String IMPORTED_FILE_NAME = "model_imported.onnx";
    private static final String IMPORTED_DEPTH_FILE_NAME = "model_depth_imported.onnx";
    private static final int BUFFER_SIZE = 1 << 16;

    private ModelStore() {
    }

    @NonNull
    public static File importedFile(@NonNull Context context) {
        return new File(context.getFilesDir(), IMPORTED_FILE_NAME);
    }

    @NonNull
    public static File importedDepthFile(@NonNull Context context) {
        return new File(context.getFilesDir(), IMPORTED_DEPTH_FILE_NAME);
    }

    /** 用户是否导入过自定义检测模型。 */
    public static boolean hasImported(@NonNull Context context) {
        return importedFile(context).isFile();
    }

    /** 用户是否导入过自定义深度模型。 */
    public static boolean hasImportedDepth(@NonNull Context context) {
        return importedDepthFile(context).isFile();
    }

    /** 是否有检测模型可用（内置或导入）。 */
    public static boolean hasModel(@NonNull Context context) {
        return hasImported(context) || assetExists(context, ASSET_NAME);
    }

    /** 是否有深度模型可用（内置或导入）。深度模型是可选的。 */
    public static boolean hasDepthModel(@NonNull Context context) {
        return hasImportedDepth(context) || assetExists(context, DEPTH_ASSET_NAME);
    }

    /**
     * 读取检测模型字节。
     *
     * @return 模型内容；两处都找不到时返回 {@code null}
     */
    @Nullable
    public static byte[] read(@NonNull Context context) throws IOException {
        return readEither(context, importedFile(context), ASSET_NAME);
    }

    /**
     * 读取深度模型字节。
     *
     * @return 模型内容；两处都找不到时返回 {@code null}（调用方据此退回启发式距离）
     */
    @Nullable
    public static byte[] readDepth(@NonNull Context context) throws IOException {
        return readEither(context, importedDepthFile(context), DEPTH_ASSET_NAME);
    }

    @Nullable
    private static byte[] readEither(@NonNull Context context, @NonNull File imported,
                                     @NonNull String assetName) throws IOException {
        if (imported.isFile()) {
            try (InputStream in = new FileInputStream(imported)) {
                return readFully(in);
            }
        }
        if (assetExists(context, assetName)) {
            try (InputStream in = context.getAssets().open(assetName)) {
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

    /** 把用户选中的文件复制到应用私有目录，覆盖上一次导入的检测模型。 */
    public static void importFrom(@NonNull Context context, @NonNull Uri uri) throws IOException {
        importInto(context, uri, importedFile(context));
    }

    /**
     * 导入深度模型。与检测模型分开存两个文件：换了检测模型不该顺带把深度模型也顶掉，
     * 反之亦然 —— 两者的来源与体积都不同，绑在一起只会让"只换一个"变成不可能。
     */
    public static void importDepthFrom(@NonNull Context context, @NonNull Uri uri)
            throws IOException {
        importInto(context, uri, importedDepthFile(context));
    }

    private static void importInto(@NonNull Context context, @NonNull Uri uri, @NonNull File target)
            throws IOException {
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

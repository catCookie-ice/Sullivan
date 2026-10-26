package com.sullivan.android.ui.common.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.BindingDtos;
import com.sullivan.android.data.api.dto.VoiceMessageDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.service.voice.VoiceRecorder;
import com.sullivan.android.ui.common.components.ServerTime;
import com.sullivan.android.ui.common.components.StateView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;

/**
 * 语音留言：录一段发给已绑定的对方，或者听对方留下的。
 *
 * <p><b>为什么要有这个功能</b>：实时会话要求两个人同时在场。监护人不在手机边上时，
 * 受护人想说的话就没了出口 —— 而「说一句话留给对方」不需要对方同时在场。
 *
 * <p><b>音频只经带令牌的端点取回</b>：服务端每次都会重新校验双方仍有生效绑定。
 * 所以这一页不能把音频链接交给系统的播放器去打开（那会是一条 401 的裸链），
 * 而是自己下载到缓存文件再播。
 */
public class VoiceMessageActivity extends AppCompatActivity {

    private static final long TICK_MS = 500L;

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, VoiceMessageActivity.class);
    }

    private SessionStore session;
    private String myUid;

    private Spinner targetSpinner;
    private TextView targetEmpty;
    private TextView recordState;
    private Button recordToggle;
    private Button recordSend;
    private Button discardButton;
    private TextView statusText;
    private LinearLayout listContainer;
    private StateView stateView;
    private Button refreshButton;

    private VoiceRecorder recorder;
    /** 录音是「开始录音」那一刻才需要的权限，所以推迟到第一次点按钮时申请。 */
    private ActivityResultLauncher<String> micPermissionLauncher;
    /** 刚录好、还没发出去的文件。 */
    @Nullable
    private File pendingFile;
    private long pendingDurationMs;

    private MediaPlayer player;
    @Nullable
    private String playingId;

    private final List<Contact> contacts = new ArrayList<>();
    private final List<VoiceMessageDtos.VoiceMessageResponse> messages = new ArrayList<>();

    private final Handler ticker = new Handler(Looper.getMainLooper());

    /** 选人下拉的一个候选项。label 给人看，uid 提交用。 */
    private static final class Contact {
        final String uid;
        final String label;

        Contact(String uid, String label) {
            this.uid = uid;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_message);

        session = new SessionStore(this);
        myUid = session.principalId();
        if (!session.isLoggedIn() || TextUtils.isEmpty(myUid)) {
            finish();
            return;
        }

        targetSpinner = findViewById(R.id.voice_target);
        targetEmpty = findViewById(R.id.voice_target_empty);
        recordState = findViewById(R.id.voice_record_state);
        recordToggle = findViewById(R.id.voice_record_toggle);
        recordSend = findViewById(R.id.voice_record_send);
        discardButton = findViewById(R.id.voice_record_discard);
        statusText = findViewById(R.id.voice_status);
        listContainer = findViewById(R.id.voice_list);
        stateView = findViewById(R.id.voice_state);
        refreshButton = findViewById(R.id.voice_refresh);

        recorder = new VoiceRecorder();

        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startRecording();
                    } else {
                        showStatus(getString(R.string.voice_need_mic_permission), true);
                    }
                });

        recordToggle.setOnClickListener(view -> toggleRecording());
        recordSend.setOnClickListener(view -> sendRecording());
        discardButton.setOnClickListener(view -> discardRecording());
        refreshButton.setOnClickListener(view -> load());

        load();
        loadContacts();
    }

    @Override
    protected void onStop() {
        // 录音与播放都不能留在后台：麦克风与扬声器被占着，用户完全不知道。
        if (recorder.isRecording()) {
            discardRecording();
        }
        stopPlayback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacksAndMessages(null);
        recorder.release();
        stopPlayback();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 联系人
    // ------------------------------------------------------------------

    private void loadContacts() {
        ApiClient.get(this).listBindings()
                .enqueue(new ResultCallback<List<BindingDtos.BindingResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<BindingDtos.BindingResponse> body) {
                        contacts.clear();
                        if (body != null) {
                            for (BindingDtos.BindingResponse binding : body) {
                                Contact contact = contactOf(binding);
                                if (contact != null) {
                                    contacts.add(contact);
                                }
                            }
                        }
                        renderContacts();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        handleUnauthorized(error);
                        renderContacts();
                    }
                });
    }

    @Nullable
    private Contact contactOf(@NonNull BindingDtos.BindingResponse binding) {
        if (!"ACTIVE".equalsIgnoreCase(binding.status)) {
            // 只有生效的绑定能留言。把待确认的也列出来，用户选了必然失败。
            return null;
        }
        boolean iAmGuardian = myUid.equals(binding.guardianId);
        String otherUid = iAmGuardian ? binding.protecteeId : binding.guardianId;
        if (TextUtils.isEmpty(otherUid)) {
            return null;
        }
        String name = iAmGuardian ? binding.protecteeName : binding.guardianName;
        String loginName = iAmGuardian ? binding.protecteeLoginName : binding.guardianLoginName;
        return new Contact(otherUid, !TextUtils.isEmpty(name) ? name
                : !TextUtils.isEmpty(loginName) ? loginName
                : getString(R.string.voice_contact_unnamed));
    }

    private void renderContacts() {
        boolean empty = contacts.isEmpty();
        targetEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        targetSpinner.setVisibility(empty ? View.GONE : View.VISIBLE);
        recordToggle.setEnabled(!empty);
        if (empty) {
            return;
        }
        ArrayAdapter<Contact> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, contacts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
    }

    // ------------------------------------------------------------------
    // 录音
    // ------------------------------------------------------------------

    private void toggleRecording() {
        if (recorder.isRecording()) {
            finishRecording();
            return;
        }
        if (contacts.isEmpty()) {
            showStatus(getString(R.string.voice_no_contact), true);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    /** 权限与联系人都已就绪，真正开始录音。 */
    private void startRecording() {
        File dir = new File(getCacheDir(), "voice");
        String error = recorder.start(dir);
        if (error != null) {
            showStatus(error, true);
            return;
        }
        pendingFile = null;
        recordSend.setEnabled(false);
        discardButton.setVisibility(View.VISIBLE);
        recordToggle.setText(R.string.voice_action_stop);
        recordState.setVisibility(View.VISIBLE);
        recordState.setTextColor(getColor(R.color.an_terracotta));
        showStatus(getString(R.string.voice_recording_hint), false);
        startTicking();
    }

    private void startTicking() {
        ticker.removeCallbacksAndMessages(null);
        ticker.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recorder.isRecording()) {
                    return;
                }
                long elapsed = recorder.elapsedMs();
                recordState.setText(getString(R.string.voice_recording_time,
                        elapsed / 1000, VoiceRecorder.MAX_DURATION_MS / 1000));
                if (elapsed >= VoiceRecorder.MAX_DURATION_MS) {
                    // 到上限自动收尾，而不是继续录到服务端拒收为止 ——
                    // 那样用户会说完一分钟才发现前功尽弃。
                    finishRecording();
                    showStatus(getString(R.string.voice_reached_limit), true);
                    return;
                }
                ticker.postDelayed(this, TICK_MS);
            }
        }, TICK_MS);
    }

    private void finishRecording() {
        ticker.removeCallbacksAndMessages(null);
        long duration = recorder.elapsedMs();
        File file = recorder.stop();
        recordToggle.setText(R.string.voice_action_start);
        recordState.setVisibility(View.GONE);
        if (file == null || duration < 500) {
            // 半秒以内的录音基本是误触。留一个「已录制 0 秒」的按钮只会让人困惑。
            pendingFile = null;
            recordSend.setEnabled(false);
            discardButton.setVisibility(View.GONE);
            showStatus(getString(R.string.voice_too_short), true);
            return;
        }
        pendingFile = file;
        pendingDurationMs = duration;
        recordSend.setEnabled(true);
        discardButton.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.voice_recorded,
                Math.round(duration / 1000.0)), false);
    }

    private void discardRecording() {
        ticker.removeCallbacksAndMessages(null);
        recorder.stop();
        if (pendingFile != null) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            pendingFile = null;
        }
        recordToggle.setText(R.string.voice_action_start);
        recordState.setVisibility(View.GONE);
        recordSend.setEnabled(false);
        discardButton.setVisibility(View.GONE);
    }

    private void sendRecording() {
        if (pendingFile == null || !pendingFile.isFile()) {
            showStatus(getString(R.string.voice_nothing_recorded), true);
            return;
        }
        Contact target = (Contact) targetSpinner.getSelectedItem();
        if (target == null) {
            showStatus(getString(R.string.voice_need_contact), true);
            return;
        }
        long size = pendingFile.length();
        recordSend.setEnabled(false);
        discardButton.setEnabled(false);

        MediaType mediaType = MediaType.parse(VoiceRecorder.CONTENT_TYPE);
        MultipartBody.Part part = MultipartBody.Part.createFormData(
                "file", pendingFile.getName(),
                RequestBody.create(pendingFile, mediaType));

        Call<VoiceMessageDtos.VoiceMessageResponse> call = ApiClient.get(this).sendVoiceMessage(
                plain(target.uid),
                plain(String.valueOf(pendingDurationMs)),
                plain(String.valueOf(size)),
                plain(VoiceRecorder.CONTENT_TYPE),
                part);
        call.enqueue(new ResultCallback<VoiceMessageDtos.VoiceMessageResponse>() {
            @Override
            public void onSuccess(@Nullable VoiceMessageDtos.VoiceMessageResponse body) {
                discardRecording();
                discardButton.setEnabled(true);
                showStatus(getString(R.string.voice_sent, target.label), false);
                load();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                recordSend.setEnabled(true);
                discardButton.setEnabled(true);
                handleUnauthorized(error);
                // 文件留着：网络失败再点一次「发送」就该成功，不该让用户重录。
                showStatus(getString(R.string.voice_send_failed, error.getMessage()), true);
            }
        });
    }

    @NonNull
    private static RequestBody plain(@NonNull String value) {
        return RequestBody.create(value, MediaType.parse("text/plain"));
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private void load() {
        refreshButton.setEnabled(false);
        stateView.showLoading(getString(R.string.state_loading));
        listContainer.setVisibility(View.GONE);
        ApiClient.get(this).listVoiceMessages()
                .enqueue(new ResultCallback<List<VoiceMessageDtos.VoiceMessageResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<VoiceMessageDtos.VoiceMessageResponse> body) {
                        refreshButton.setEnabled(true);
                        render(body);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        refreshButton.setEnabled(true);
                        handleUnauthorized(error);
                        stateView.showError(error.getMessage(), () -> load());
                    }
                });
    }

    private void render(@Nullable List<VoiceMessageDtos.VoiceMessageResponse> items) {
        messages.clear();
        if (items != null) {
            messages.addAll(items);
        }
        rerender();
    }

    /** 按当前的 {@link #messages} 重画列表。播放状态变化走这条，不重拉接口。 */
    private void rerender() {
        listContainer.removeAllViews();
        if (messages.isEmpty()) {
            stateView.showEmpty(getString(R.string.voice_empty));
            listContainer.setVisibility(View.GONE);
            return;
        }
        stateView.hide();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (VoiceMessageDtos.VoiceMessageResponse item : messages) {
            View row = inflater.inflate(R.layout.row_voice_message, listContainer, false);
            bindRow(row, item);
            listContainer.addView(row);
        }
        listContainer.setVisibility(View.VISIBLE);
    }

    private void bindRow(@NonNull View row, @NonNull VoiceMessageDtos.VoiceMessageResponse item) {
        boolean received = VoiceMessageDtos.ROLE_RECEIVER.equals(item.myRole);
        boolean listened = VoiceMessageDtos.STATUS_LISTENED.equals(item.status);

        TextView status = row.findViewById(R.id.voice_row_status);
        if (received) {
            status.setText(listened ? R.string.voice_status_listened : R.string.voice_status_new);
            status.setBackgroundResource(listened
                    ? R.drawable.an_chip_neutral : R.drawable.an_chip_warning);
        } else {
            status.setText(listened ? R.string.voice_status_heard : R.string.voice_status_sent);
            status.setBackgroundResource(R.drawable.an_chip_neutral);
        }

        ((TextView) row.findViewById(R.id.voice_row_side)).setText(
                received ? R.string.voice_side_received : R.string.voice_side_sent);

        String who = received ? displayName(item.senderName, item.senderLoginName)
                : displayName(item.receiverName, item.receiverLoginName);
        ((TextView) row.findViewById(R.id.voice_row_title)).setText(
                getString(received ? R.string.voice_row_from : R.string.voice_row_to, who));

        ((TextView) row.findViewById(R.id.voice_row_meta)).setText(
                getString(R.string.voice_row_meta,
                        Math.round(item.durationMs / 1000.0),
                        ServerTime.shortForm(item.createdAt)));

        TextView playback = row.findViewById(R.id.voice_row_playback);
        boolean isPlaying = item.id != null && item.id.equals(playingId);
        playback.setVisibility(isPlaying ? View.VISIBLE : View.GONE);

        Button play = row.findViewById(R.id.voice_row_play);
        play.setText(isPlaying ? R.string.voice_action_stop_play : R.string.voice_action_play);
        play.setOnClickListener(view -> {
            if (isPlaying) {
                stopPlayback();
                rerender();
            } else {
                play(item, row);
            }
        });

        // 「标记已听」只对「收到且还没标过」有意义。发出方点它没有任何语义。
        Button markListened = row.findViewById(R.id.voice_row_listened);
        boolean canMark = received && !listened;
        markListened.setVisibility(canMark ? View.VISIBLE : View.GONE);
        if (canMark) {
            markListened.setOnClickListener(view -> markListened(item));
        }

        row.findViewById(R.id.voice_row_delete).setOnClickListener(
                view -> confirmDelete(item));
    }

    @NonNull
    private String displayName(@Nullable String name, @Nullable String loginName) {
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        if (!TextUtils.isEmpty(loginName)) {
            return loginName;
        }
        return getString(R.string.voice_contact_unnamed);
    }

    private void markListened(@NonNull VoiceMessageDtos.VoiceMessageResponse item) {
        if (item.id == null) {
            return;
        }
        ApiClient.get(this).markVoiceMessageListened(item.id)
                .enqueue(new ResultCallback<VoiceMessageDtos.VoiceMessageResponse>() {
                    @Override
                    public void onSuccess(@Nullable VoiceMessageDtos.VoiceMessageResponse body) {
                        load();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        handleUnauthorized(error);
                        showStatus(getString(R.string.voice_mark_failed, error.getMessage()), true);
                    }
                });
    }

    private void confirmDelete(@NonNull VoiceMessageDtos.VoiceMessageResponse item) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.voice_confirm_delete)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> delete(item))
                .show();
    }

    private void delete(@NonNull VoiceMessageDtos.VoiceMessageResponse item) {
        if (item.id == null) {
            return;
        }
        if (item.id.equals(playingId)) {
            stopPlayback();
        }
        ApiClient.get(this).deleteVoiceMessage(item.id)
                .enqueue(new ResultCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void body) {
                        // 204：没有响应体是正常的。
                        showStatus(getString(R.string.voice_deleted), false);
                        load();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        handleUnauthorized(error);
                        showStatus(getString(R.string.voice_delete_failed, error.getMessage()), true);
                    }
                });
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    /**
     * 播放一条留言。
     *
     * <p>必须先把音频下载到本地文件再播：服务端的音频端点要求 Bearer 令牌，
     * 把它直接交给 MediaPlayer 或系统播放器只会有 401。下载到缓存后播放，
     * 顺便还能避免边下边播时因为网络抖动把声音打断。
     */
    private void play(@NonNull VoiceMessageDtos.VoiceMessageResponse item, @NonNull View row) {
        if (item.id == null) {
            return;
        }
        stopPlayback();
        playingId = item.id;
        bindRow(row, item);
        displayStatus(getString(R.string.voice_downloading), false);

        ApiClient.get(this).voiceMessageAudio(item.id)
                .enqueue(new ResultCallback<ResponseBody>() {
                    @Override
                    public void onSuccess(@Nullable ResponseBody body) {
                        if (body == null) {
                            playingId = null;
                            displayStatus(getString(R.string.voice_play_failed, "响应为空"), true);
                            load();
                            return;
                        }
                        File file = writeToCache(item.id, body);
                        if (file == null) {
                            playingId = null;
                            displayStatus(getString(R.string.voice_play_failed, "无法写入缓存"), true);
                            rerender();
                            return;
                        }
                        startPlayer(file, item);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        playingId = null;
                        handleUnauthorized(error);
                        displayStatus(getString(R.string.voice_play_failed, error.getMessage()), true);
                        rerender();
                    }
                });
    }

    @Nullable
    private File writeToCache(@NonNull String id, @NonNull ResponseBody body) {
        File target = new File(new File(getCacheDir(), "voice"), "play-" + id + ".m4a");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return null;
        }
        try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return target;
        } catch (IOException error) {
            return null;
        } finally {
            body.close();
        }
    }

    private void startPlayer(@NonNull File file, @NonNull VoiceMessageDtos.VoiceMessageResponse item) {
        MediaPlayer created = new MediaPlayer();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            created.setDataSource(file.getAbsolutePath());
            created.setOnCompletionListener(finished -> {
                stopPlayback();
                // 播完了把按钮从「停止」变回「播放」。
                rerender();
            });
            created.setOnErrorListener((player, what, extra) -> {
                playingId = null;
                displayStatus(getString(R.string.voice_play_failed,
                        getString(R.string.voice_play_error_decoder)), true);
                stopPlayback();
                rerender();
                return true;
            });
            created.prepare();
            created.start();
            player = created;
            displayStatus(getString(R.string.voice_playing), false);
            // 只重画本地列表，**不**重新拉接口：拉接口会先把列表整个换成加载态，
            // 正在听的这一条会从屏幕上消失一下，看着像播放崩了。
            rerender();
        } catch (IOException | IllegalStateException error) {
            created.release();
            playingId = null;
            displayStatus(getString(R.string.voice_play_failed, error.getMessage()), true);
            rerender();
        }
    }

    /**
     * 停止播放并释放 MediaPlayer。
     *
     * <p><b>不在这里重画列表</b>：它也会从 {@code onStop}/{@code onDestroy} 里被调用，
     * 那时碰 View 是没有意义的（甚至可能已经 detach）。需要刷新按钮文字的地方
     * （用户点「停止」）自己随后调 {@link #rerender()}。
     */
    private void stopPlayback() {
        MediaPlayer current = player;
        player = null;
        playingId = null;
        if (current != null) {
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                // 已经停了。
            }
            current.release();
        }
    }

    // ------------------------------------------------------------------

    private void showStatus(@NonNull String message, boolean isError) {
        displayStatus(message, isError);
    }

    private void displayStatus(@NonNull String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    private void handleUnauthorized(@NonNull ApiException error) {
        if (error.isUnauthorized()) {
            session.clearLogin();
        }
    }

    private void toast(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

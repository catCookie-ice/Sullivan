package com.sullivan.protectee.data.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 统一处理「非 2xx → 中文原因」与「连不上」这两件事的回调基类。
 *
 * <p>没有它的话，每一处界面都要写一遍
 * {@code response.isSuccessful() → errorBody().string() → Gson → 兜底文案}，
 * 五六个界面各写一遍必然有写得不一样的，最后表现成「同一个错误在不同页面提示不同」。
 *
 * <p><b>回调在主线程</b>：Retrofit 在 Android 上默认把回调投递到主线程，
 * 所以 {@link #onSuccess} / {@link #onError} 里可以直接碰 View。
 *
 * <p>服务端返回 <b>204</b>（如「当前没有有效绑定码」）时 body 为空，
 * 这时 {@link #onSuccess} 收到的是 {@code null}，由调用方按「无内容」处理 ——
 * 这不是错误，别做成弹窗。
 */
public abstract class ResultCallback<T> implements Callback<T> {

    /** 成功且服务端给了 body。204 时 {@code body} 为 null。 */
    public abstract void onSuccess(@Nullable T body);

    /** 失败。{@code error.getMessage()} 已是可直接展示的中文。 */
    public abstract void onError(@NonNull ApiException error);

    @Override
    public final void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
        if (response.isSuccessful()) {
            onSuccess(response.body());
            return;
        }
        onError(ApiClient.toApiException(response));
    }

    @Override
    public final void onFailure(@NonNull Call<T> call, @NonNull Throwable throwable) {
        // 请求被主动取消不算错误：界面已经销毁或用户已经离开，再弹提示反而吓人。
        if (call.isCanceled()) {
            return;
        }
        onError(ApiClient.transportFailure(throwable));
    }
}

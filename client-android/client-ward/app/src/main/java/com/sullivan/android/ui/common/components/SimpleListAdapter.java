package com.sullivan.android.ui.common.components;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「一行一个对象、每行布局各不相同」的通用适配器。
 *
 * <p><b>不泛型化 ViewHolder</b>：四个列表（绑定、活动、申请、会话）的行布局字段完全不同，
 * 抽一个 {@code BaseViewHolder} 只能抽到「找不到视图会崩」这一件事，
 * 而这件事本来就该崩。所以这里只做三件真的会重复写的事：持有列表、
 * 在更新时换掉整份数据、把 inflate + bind 串起来。
 *
 * <p><b>更新一律 {@code notifyDataSetChanged}</b>：列表最多几十行，
 * 而每次刷新后各行的**可用按钮**（绑定关系随状态变）都可能不同 ——
 * 写一套 diff 逻辑去省这点重绘，换来的是「确认之后按钮还在」这类难复现的 bug。
 *
 * <p>数据源是防御性拷贝：四个页面都从 Retrofit 的回调里拿到 list 后直接塞进来，
 * 而 Retrofit 的 list 可能是不可变的（Gson 默认给 ArrayList，但不要依赖这一点）。
 */
public class SimpleListAdapter<T> extends RecyclerView.Adapter<SimpleListAdapter.Row> {

    /** 把一份数据画到一行上。 */
    public interface Binder<T> {
        void bind(@NonNull View row, @NonNull T item);
    }

    private final int rowLayoutRes;
    private final Binder<T> binder;
    private final List<T> items = new ArrayList<>();

    public SimpleListAdapter(@LayoutRes int rowLayoutRes, @NonNull Binder<T> binder) {
        this.rowLayoutRes = rowLayoutRes;
        this.binder = binder;
    }

    /**
     * 换掉整份数据。
     *
     * @param list 可以是 null（调用方多半是 {@code response.body()}，204 时就是 null），
     *             按空列表处理。
     */
    public void submit(@NonNull List<T> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    public T item(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(rowLayoutRes, parent, false);
        return new Row(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position) {
        binder.bind(holder.row, items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 只包一层 View：行内的控件由 {@link Binder} 自己 findViewById。 */
    public static final class Row extends RecyclerView.ViewHolder {
        final View row;

        Row(@NonNull View row) {
            super(row);
            this.row = row;
        }
    }
}

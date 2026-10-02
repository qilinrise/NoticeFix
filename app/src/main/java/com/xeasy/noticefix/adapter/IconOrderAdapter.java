package com.xeasy.noticefix.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.xeasy.noticefix.R;
import com.xeasy.noticefix.bean.IconFunc;
import com.xeasy.noticefix.dao.IconFuncDao;

import java.util.Collections;
import java.util.List;

public class IconOrderAdapter extends RecyclerView.Adapter<IconOrderAdapter.ViewHolder> {
    private final List<IconFuncDao.IconFuncStatus> list;
    private final Context context;
    private final ItemTouchHelper itemTouchHelper;

    public IconOrderAdapter(List<IconFuncDao.IconFuncStatus> list, RecyclerView recyclerView, Context context) {
        this.list = list;
        this.context = context;

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getAdapterPosition();
                int toPosition = target.getAdapterPosition();
                Collections.swap(list, fromPosition, toPosition);
                notifyItemMoved(fromPosition, toPosition);
                IconFuncDao.saveSwap(context, fromPosition, toPosition);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        };
        this.itemTouchHelper = new ItemTouchHelper(callback);
        this.itemTouchHelper.attachToRecyclerView(recyclerView);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.icon_config_lis, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IconFuncDao.IconFuncStatus item = list.get(position);

        // 使用已有的枚举常量 funcId 安全匹配名称，不依赖任何未知变量
        String title;
        if (item.iconFuncId == IconFunc.LIB_FIX.funcId) {
            title = "图标库";
        } else if (item.iconFuncId == IconFunc.CUSTOM_FIX.funcId) {
            title = "自定义图标";
        } else if (item.iconFuncId == IconFunc.AUTO_FIX.funcId) {
            title = "提取算法";
        } else {
            title = "功能 " + item.iconFuncId;
        }
        holder.tv.setText(title);

        holder.status.setOnCheckedChangeListener(null);
        holder.status.setChecked(item.active);
        holder.status.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.active = isChecked;
            IconFuncDao.save(context, item);
        });

        if (holder.dragButton != null) {
            holder.dragButton.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });
        }
    }

    @Override
    public int getItemCount() {
        return list == null ? 0 : list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView tv;
        public SwitchCompat status;
        public ImageView dragButton;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tv = itemView.findViewById(R.id.icon_config_content);
            status = itemView.findViewById(R.id.icon_config_switchCompat);
            dragButton = itemView.findViewById(R.id.icon_config_content_order);
        }
    }
}

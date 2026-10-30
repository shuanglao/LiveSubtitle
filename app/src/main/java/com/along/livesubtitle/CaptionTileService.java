package com.along.livesubtitle;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：下拉通知栏点一下即可开启/关闭实时字幕。
 * 免 root、无额外权限，系统自带入口，任何界面都能触发。
 */
public class CaptionTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh(false);
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        boolean running = (tile != null && tile.getState() == Tile.STATE_ACTIVE);
        Intent svc = new Intent(this, CaptionService.class);
        svc.setAction(running ? CaptionService.ACTION_STOP : CaptionService.ACTION_START);
        if (running) {
            startService(svc);
            refresh(true);
        } else {
            // 需先在 App 内授予录音/悬浮窗权限；未授权时拉起 App 引导
            if (!canOverlay() || !canRecord()) {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(i);
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            refresh(true);
        }
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private boolean canRecord() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh(boolean active) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(active ? "字幕：开" : "实时字幕");
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(active ? "点击关闭" : "点击开启");
        }
        t.updateTile();
    }
}

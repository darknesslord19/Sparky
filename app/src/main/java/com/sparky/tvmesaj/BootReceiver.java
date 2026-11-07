package com.sparky.tvmesaj;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** TV acildiginda mesaj servisini otomatik baslatir. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        MessageService.start(context);
    }
}

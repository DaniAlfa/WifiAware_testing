package com.example.wifiawarecom;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class WifiAwareViewModel extends AndroidViewModel {

    private WifiAwareManager manager;
    private WifiAwareSession session;
    private MutableLiveData<Boolean> available;
    private HandlerThread worker;
    private Handler workerHandle;

    public WifiAwareViewModel(@NonNull Application app) {
        super(app);
        available = new MutableLiveData<Boolean>(Boolean.FALSE);
        session = null;

        if(!app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)){
            manager = null;
            worker = null;
            return;
        }
        worker = new HandlerThread("Worker");
        worker.start();
        workerHandle = new Handler(worker.getLooper());

        manager = (WifiAwareManager)app.getSystemService(app.WIFI_AWARE_SERVICE);
        IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
        BroadcastReceiver myReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkWifiAwareAvailability();
            }
        };
        app.registerReceiver(myReceiver, filter);
        checkWifiAwareAvailability();
    }

    public boolean isWifiAwareSupported(){
        return manager != null;
    }

    private void checkWifiAwareAvailability(){
        synchronized (this) {
            if (session != null) {
                session.close();
                session = null;
            }
        }
        if(manager.isAvailable()){
            available.setValue(Boolean.TRUE);
        }
        else{
            available.setValue(Boolean.FALSE);
        }
    }

    public LiveData<Boolean> isWifiAwareAvailable(){
        return available;
    }

    public synchronized boolean createSession() throws InterruptedException {
        if(manager == null) return false;

        manager.attach(new AttachCallback(){
            @Override
            public void onAttached(WifiAwareSession session) {
                synchronized (WifiAwareViewModel.this){
                    WifiAwareViewModel.this.session = session;
                    WifiAwareViewModel.this.notify();
                }
            }

            @Override
            public void onAttachFailed() {
                synchronized (WifiAwareViewModel.this){
                    WifiAwareViewModel.this.session = null;
                    WifiAwareViewModel.this.notify();
                }
            }
        }, workerHandle);
        this.wait();
        return session != null;
    }

}

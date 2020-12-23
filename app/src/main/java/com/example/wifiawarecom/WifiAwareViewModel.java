package com.example.wifiawarecom;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.List;

public class WifiAwareViewModel extends AndroidViewModel {

    private ConnectivityManager connectivityManager;
    private WifiAwareManager manager;
    private WifiAwareSession session;
    private PublishDiscoverySession publishSession;
    private SubscribeDiscoverySession subscribeSession;
    private Server publishServer;
    private Client subscriberClient;
    private MutableLiveData<Boolean> available;
    private HandlerThread worker;
    private Handler workerHandle;

    private MutableLiveData<String> clientData;

    public WifiAwareViewModel(@NonNull Application app) {
        super(app);
        available = new MutableLiveData<Boolean>(Boolean.FALSE);
        clientData = new MutableLiveData<String>("");
        session = null;
        publishSession = null;
        subscribeSession = null;
        subscriberClient = null;
        publishServer = null;

        if(!app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)){
            manager = null;
            worker = null;
            return;
        }
        worker = new HandlerThread("Worker");
        worker.start();
        workerHandle = new Handler(worker.getLooper());

        connectivityManager = (ConnectivityManager) app.getSystemService(app.CONNECTIVITY_SERVICE);
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

    @Override
    protected void onCleared() {
        closeSessions();
    }

    public boolean isWifiAwareSupported(){
        return manager != null;
    }

    private void checkWifiAwareAvailability(){
        if (session != null) {
            if(publishSession != null){
                publishSession.close();
                publishSession = null;
            }
            session.close();
            session = null;
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

    public  LiveData<String> getClientData(){return clientData;}

    public void setClientData(String clientData) {
        this.clientData.setValue(clientData);
    }

    public boolean createSession() throws InterruptedException {
        if(manager == null) return false;
        if(session != null) return true;
        synchronized (this){
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
                        session = null;
                        WifiAwareViewModel.this.notify();
                    }
                }
            }, workerHandle);
            this.wait();
            return session != null;
        }
    }

    public boolean publishService(String serviceName) throws InterruptedException {
        if(session == null) return false;
        synchronized (WifiAwareViewModel.this){
            PublishConfig config = new PublishConfig.Builder().setServiceName(serviceName).build();

            session.publish(config, new DiscoverySessionCallback(){
                @Override
                public void onPublishStarted(@NonNull PublishDiscoverySession session) {
                    synchronized (WifiAwareViewModel.this){
                        publishSession = session;
                        try {
                            publishServer = new Server(connectivityManager, WifiAwareViewModel.this);
                            publishServer.start();
                        } catch (IOException e) {
                            session.close();
                            publishSession = null;
                        }
                        WifiAwareViewModel.this.notify();
                    }
                }

                @Override
                public void onSessionConfigFailed() {
                    synchronized (WifiAwareViewModel.this){
                        publishSession = null;
                        WifiAwareViewModel.this.notify();
                    }
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    publishServer.addNewConnection(publishSession, peerHandle);
                }

            }, workerHandle);

            this.wait();
            return publishSession != null;
        }
    }

    public boolean subscribeToService(String serviceName) throws InterruptedException {
        if(session == null) return false;
        synchronized (WifiAwareViewModel.this){
            SubscribeConfig config = new SubscribeConfig.Builder().setServiceName(serviceName).build();
            session.subscribe(config, new DiscoverySessionCallback(){

                private PeerHandle lastPeerHandle;
                @Override
                public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
                    synchronized (WifiAwareViewModel.this){
                        subscribeSession = session;
                        WifiAwareViewModel.this.notify();
                    }
                }

                @Override
                public void onSessionConfigFailed() {
                    synchronized (WifiAwareViewModel.this){
                        subscribeSession = null;
                        WifiAwareViewModel.this.notify();
                    }
                }

                @Override
                public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                    lastPeerHandle = peerHandle;
                    subscribeSession.sendMessage(peerHandle, 0, (new String("connect")).getBytes());
                }

                @Override
                public void onMessageSendSucceeded(int messageId) {
                    subscriberClient = new Client(connectivityManager, subscribeSession, lastPeerHandle);
                }


            }, workerHandle);

            this.wait();
            return subscribeSession != null;
        }
    }

    public void closeSessions(){
        if(publishSession != null){
            publishSession.close();
            publishServer.stop();
            publishServer = null;
            publishSession = null;
        }
        if(subscribeSession != null){
            subscribeSession.close();
            subscriberClient.stop();
            subscriberClient = null;
            subscribeSession = null;
        }
        if(session != null){
            session.close();
            session = null;
        }
    }

}

package com.example.wifiawarecom;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;


public class Client implements Runnable{

    private static final String TAG = "Client";
    private static final int BUFFER_SIZE = 8192;

    private final ConnectivityManager mConnManager;
    private Network mCurrentNet;
    private NetworkCapabilities mCurrentNetCapabitities;
    private SocketChannel mSocketChannel_2;
    private SocketChannel mSocketChannel_1;
    private ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFFER_SIZE);

    private Thread mClientThread;
    private boolean mEnabled = false;

    public Client(ConnectivityManager manager, DiscoverySession subscribeSession, PeerHandle handle){
        mCurrentNetCapabitities = null;
        mCurrentNet = null;
        mSocketChannel_1 = null;
        mSocketChannel_2 = null;
        mConnManager = manager;
        NetworkSpecifier networkSpecifier = new WifiAwareNetworkSpecifier.Builder(subscribeSession, handle)
                .setPskPassphrase("wifiawaretest")
                .build();
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build();
        mConnManager.requestNetwork(networkRequest, new WifiAwareNetworkCallback());
    }

    public void start(){
        if(!mEnabled && mCurrentNetCapabitities != null) {
            mEnabled = true;
            mClientThread = new Thread(this);
            mClientThread.start();
        }
    }

    public void stop(){
        if(mEnabled){
            mEnabled = false;
            mClientThread.interrupt();
        }
    }

    @Override
    public void run() {
        try {
            if(!mConnManager.bindProcessToNetwork(mCurrentNet)) return;
            WifiAwareNetworkInfo peerAwareInfo = (WifiAwareNetworkInfo) mCurrentNetCapabitities.getTransportInfo();
            InetAddress peerIpv6 = peerAwareInfo.getPeerIpv6Addr();
            int peerPort = peerAwareInfo.getPort();
            mSocketChannel_1 = SocketChannel.open();
            mSocketChannel_1.connect(new InetSocketAddress(peerIpv6, peerPort));
            mSocketChannel_2 = SocketChannel.open();
            mSocketChannel_2.connect(new InetSocketAddress(peerIpv6, peerPort));
            int i = 0;
            int j = 100;
            while(mEnabled){
                mWriteBuffer.clear();
                mWriteBuffer = ByteBuffer.wrap(String.valueOf(i).getBytes(StandardCharsets.UTF_8));
                mSocketChannel_1.write(mWriteBuffer);
                ++i;
                i %= 11;
                mWriteBuffer.clear();
                mWriteBuffer = ByteBuffer.wrap(String.valueOf(j).getBytes(StandardCharsets.UTF_8));
                mSocketChannel_2.write(mWriteBuffer);
                --j;
                if(j < 0) j = 100;
                Thread.sleep(1000);
            }
            mSocketChannel_1.close();
            mSocketChannel_2.close();
        } catch (IOException ex1){
            Log.d(TAG, "Client run: " + ex1.toString());
        }
        catch (InterruptedException ex2){
            try {
                mSocketChannel_1.close();
                mSocketChannel_2.close();
            } catch (IOException e) {}
        }
        finally {
            mEnabled = false;
            mClientThread = null;
            mSocketChannel_1 = null;
            mSocketChannel_2 = null;
            mCurrentNetCapabitities = null;
            mCurrentNet = null;
        }
    }


    private class WifiAwareNetworkCallback extends ConnectivityManager.NetworkCallback{

        @Override
        public void onAvailable(@NonNull Network network) {
            mCurrentNet = network;
        }

        //Debugeando he visto que se llama dos veces a este callback al conectarse
        //En una primera conexion cliente-servidor, con el objeto NetworkCapabilities de la primera llamada es suficiente, la segunda se puede ignorar
        //En cambio si cierras el servidor y lo vuelves a abrir, el cliente intentara conectarse arrancando el thread en la primera llamada y fallara.
        //En la segunda cogiendo el segundo objeto NetworkCapabilities si funciona
        //Es un poco raro hay que revisarlo

        //He visto que lo que cambia entre las llamadas es la direccion IPv6
        //Cuando tiene como prefijo %aware_data0 funciona, si no al intentar conectar salta excepcion de argumento invalido
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            WifiAwareNetworkInfo peerAwareInfo = (WifiAwareNetworkInfo) networkCapabilities.getTransportInfo();
            InetAddress peerIpv6 = peerAwareInfo.getPeerIpv6Addr();
            int peerPort = peerAwareInfo.getPort();
            if(mCurrentNetCapabitities == null){
                mCurrentNetCapabitities = networkCapabilities;
                if(!mEnabled) start();
            }
            else Log.d(TAG, "onCapabilitiesChanged: Red distinta o nueva capability");
        }

        @Override
        public void onLost(@NonNull Network network) {
            mConnManager.unregisterNetworkCallback(this);
            stop();
        }
    }
}

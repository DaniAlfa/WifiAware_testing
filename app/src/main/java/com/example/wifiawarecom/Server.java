package com.example.wifiawarecom;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Server implements Runnable{
    private static final String TAG = "Server";

    private static final int BUFFER_SIZE = 8192;

    private final WifiAwareViewModel mModel;
    private final Selector mSelector;
    private Thread mServerThread;
    private boolean mEnabled = false;
    private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private Map<PeerHandle, Connection> mConnectionsMap;
    private Map<ServerSocketChannel, Connection> mServerChannelsMap;
    private ConnectivityManager mConManager;
    private final Lock mSelectorLock = new ReentrantLock();

    public Server(ConnectivityManager manager, WifiAwareViewModel model) throws IOException {
        mModel = model;
        mSelector = Selector.open();
        mConnectionsMap = new HashMap<>();
        mServerChannelsMap = new HashMap<>();
        mConManager = manager;
    }

    public void start(){
        if(!mEnabled) {
            mEnabled = true;
            mServerThread = new Thread(this);
            mServerThread.start();
        }
    }

    public void stop(){
        if(mEnabled){
            mEnabled = false;
            mServerThread.interrupt();
            closeAllConnections();
            try {
                mSelector.close();
            } catch (IOException e) {}
        }
    }

    @Override
    public void run() {

        mSelectorLock.lock();
        while(mEnabled){
            try {
                mSelectorLock.unlock();
                mSelector.select();
                mSelectorLock.lock();
                Iterator<SelectionKey> itKeys = mSelector.selectedKeys().iterator();
                while (itKeys.hasNext()) {
                    SelectionKey myKey = itKeys.next();
                    itKeys.remove();

                    if (!myKey.isValid()) {
                        continue;
                    }

                    if (myKey.isAcceptable()) {
                        accept(myKey);
                    } else if (myKey.isReadable()) {
                        this.read(myKey);
                    }
                }
            } catch (IOException e) {
                mEnabled = false;
            }
        }
        mSelectorLock.unlock();

    }

    public synchronized boolean addNewConnection(DiscoverySession discoverySession, PeerHandle handle){
        if(mConnectionsMap.get(handle) != null){
            return true;
        }
        if(!mEnabled) return false;
        ServerSocketChannel serverSocketChannel = null;
        int mServerPort;
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(0));
            mServerPort = serverSocketChannel.socket().getLocalPort();
            NetworkSpecifier networkSpecifier = new WifiAwareNetworkSpecifier.Builder(discoverySession, handle)
                    .setPskPassphrase("wifiawaretest")
                    .setPort(mServerPort)
                    .build();
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(networkSpecifier)
                    .build();
            mSelectorLock.lock();
            mSelector.wakeup();
            try{
                SelectionKey serverKey = serverSocketChannel.register(mSelector, SelectionKey.OP_ACCEPT);
            }catch (IOException ex){
                mSelectorLock.unlock();
                serverSocketChannel.close();
                throw ex;
            }
            mSelectorLock.unlock();
            Connection conn = new Connection(serverSocketChannel, handle);
            mConnectionsMap.put(handle, conn);
            mServerChannelsMap.put(serverSocketChannel, conn);
            mConManager.requestNetwork(networkRequest, new WifiAwareNetworkCallback(handle));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public synchronized void removeConnection(PeerHandle handle) {
        Connection conn =  mConnectionsMap.get(handle);
        ServerSocketChannel serverChan = null;
        if(conn == null){
            return;
        }
        mSelectorLock.lock();
        mSelector.wakeup();
        try {
            serverChan = conn.mServerSocketChannel;
            serverChan.keyFor(mSelector).cancel();
            serverChan.close();
            for(SocketChannel chan : conn.mComChannels){
                chan.keyFor(mSelector).cancel();
                chan.close();
            }
        } catch (IOException e) {}
        mSelectorLock.unlock();
        mConnectionsMap.remove(handle);
        mServerChannelsMap.remove(serverChan);
    }

    private synchronized void closeAllConnections(){
        mSelectorLock.lock();
        for(Connection conn : mConnectionsMap.values()){
            try {
                ServerSocketChannel serverChan = conn.mServerSocketChannel;
                serverChan.keyFor(mSelector).cancel();
                serverChan.close();
                for(SocketChannel chan : conn.mComChannels){
                    chan.keyFor(mSelector).cancel();
                    chan.close();
                }
            } catch (IOException e) {}
        }
        mConnectionsMap.clear();
        mServerChannelsMap.clear();
        mSelectorLock.unlock();
    }

    private synchronized void accept(@NonNull SelectionKey key){
        ServerSocketChannel serverChan = (ServerSocketChannel) key.channel();
        Connection conn = mServerChannelsMap.get(serverChan);
        if(conn == null){
            return;
        }
        SocketChannel socketChannel = null;
        try {
            socketChannel = serverChan.accept();
            socketChannel.configureBlocking(false);// Accept the connection and make it non-blocking
            socketChannel.register(mSelector, SelectionKey.OP_READ);
            conn.mComChannels.add(socketChannel);
        } catch (IOException e) {
            try {
                serverChan.keyFor(mSelector).cancel();
                serverChan.close();
                for(SocketChannel chan : conn.mComChannels){
                    chan.keyFor(mSelector).cancel();
                    chan.close();
                }
            }
            catch (IOException ex){}
            mConnectionsMap.remove(conn.handle);
            mServerChannelsMap.remove(serverChan);
        }
    }

    private void read(@NonNull SelectionKey key){
        int numRead;
        SelectableChannel socketChannel = key.channel();
        mReadBuffer.clear();
        try {
            numRead = ((ByteChannel) socketChannel).read(mReadBuffer);
            if(numRead != -1){
                byte[] bytes = mReadBuffer.array();
                String v = new String(bytes, 0, numRead, StandardCharsets.UTF_8);
                Log.d(TAG, "read: " + v);
                mModel.setClientData(v);
            }
        } catch (IOException e) {
            socketChannel.keyFor(mSelector).cancel();
        }
    }

    public static String bb_to_str(ByteBuffer buffer, Charset charset){
        byte[] bytes;
        if(buffer.hasArray()) {
            bytes = buffer.array();
        } else {
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        }
        return new String(bytes, charset);
    }

    private class Connection{

        public Connection(ServerSocketChannel serverChan, PeerHandle handle){
            mServerSocketChannel = serverChan;
            this.handle = handle;
            mComChannels = new ArrayList<>();
        }
        public ServerSocketChannel mServerSocketChannel;
        public PeerHandle handle;
        public Network net;
        public List<SocketChannel> mComChannels;
    }

    private class WifiAwareNetworkCallback extends ConnectivityManager.NetworkCallback{

        PeerHandle mConnectionHandle;

        public WifiAwareNetworkCallback(PeerHandle connectionHandle){
            this.mConnectionHandle = connectionHandle;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            synchronized (Server.this){
                Connection conn = mConnectionsMap.get(mConnectionHandle);
                if(conn == null){
                    return;
                }
                conn.net = network;
            }
        }


        @Override
        public void onLost(@NonNull Network network) {
            mConManager.unregisterNetworkCallback(this);
            removeConnection(mConnectionHandle);
        }
    }
}

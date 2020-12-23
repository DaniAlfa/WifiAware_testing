package com.example.wifiawarecom;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private Button mPublisherButton, mSubscriberButton;
    private TextView mClientData;
    private WifiAwareViewModel mAwareModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();

        mAwareModel = new ViewModelProvider(this, new ViewModelProvider.AndroidViewModelFactory(getApplication())).get(WifiAwareViewModel.class);

        if(!mAwareModel.isWifiAwareSupported()){
            setControlsEnabled(false);
            Toast.makeText(this, R.string.no_wifiAware_feature, Toast.LENGTH_SHORT).show();
            return;
        }

        mAwareModel.isWifiAwareAvailable().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean available) {
                if (available) {
                    setControlsEnabled(true);
                    Toast.makeText(MainActivity.this, "WifiAware esta disponible", Toast.LENGTH_SHORT).show();
                } else {
                    setControlsEnabled(false);
                    Toast.makeText(MainActivity.this, "WifiAware no esta disponible, active Wifi o desactive WifiDirect", Toast.LENGTH_SHORT).show();
                }
            }
        });

        mAwareModel.getClientData().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                mClientData.setText(s);
            }
        });
    }

    private void initView(){
        mPublisherButton = (Button) findViewById(R.id.publisher_button);
        mPublisherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishService();
            }
        });
        mSubscriberButton = (Button) findViewById(R.id.subscriber_button);
        mSubscriberButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                subscribeToService();
            }
        });

        mClientData = (TextView) findViewById(R.id.clientData);
    }

    private void setControlsEnabled(boolean b){
        mPublisherButton.setEnabled(b);
        mSubscriberButton.setEnabled(b);
    }

    private void publishService(){
        setControlsEnabled(false);
        try {
            if(mAwareModel.createSession()){
                if(mAwareModel.publishService("Server")){
                    Toast.makeText(this, "Se publico un nuevo servicio con WifiAware", Toast.LENGTH_SHORT).show();
                }
                else{
                    Toast.makeText(this, "No se pudo publicar un servicio de WifiAware", Toast.LENGTH_SHORT).show();
                    setControlsEnabled(true);
                }
            }else {
                Toast.makeText(this, "No se pudo crear la sesion de WifiAware", Toast.LENGTH_SHORT).show();
                setControlsEnabled(true);
            }
        } catch (InterruptedException e) {}
    }

   private void subscribeToService(){
       setControlsEnabled(false);

   }

}
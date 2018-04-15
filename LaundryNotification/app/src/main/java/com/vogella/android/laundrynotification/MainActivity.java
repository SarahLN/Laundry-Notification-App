package com.vogella.android.laundrynotification;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.NetworkOnMainThreadException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.module.Accelerometer;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import bolts.Continuation;
import bolts.Task;

/**
 * Main Activity for the app.  Establishes layout and creates board object
 */
public class MainActivity extends AppCompatActivity implements ServiceConnection {
    private BtleService.LocalBinder serviceBinder;
    private final String MW_MAC_ADDRESS= "DA:62:2D:9A:D5:8D";
    private MetaWearBoard board;
    private Accelerometer accelerometer;
    private MachineStatus machineStatus;
    private NotificationUtil notifications;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar mainToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(mainToolbar);

        // establish the machine status tracker and update the
        // view to display that status
        this.setMachineStatus(MachineStatus.OFF);
        this.setMachineStatusValue();

        // establish notification utility
        this.notifications = new NotificationUtil();

        // Bind the Metawear Btle service when the activity is created
        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);
        Log.i("AppLog", "onCreate method called");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuItem_settings:
                // User selected the settings menu item
                // TODO: Open the settings UI
                Toast.makeText(this, "Settings Selected", Toast.LENGTH_SHORT).show();
                return true;
            default:
                // The users action was not recognized so call super class
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Unbind the Metawear Btle service when the activity is destroyed
        getApplicationContext().unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        // Typecast the binder to the Metawear Btle service's LocalBinder class
        serviceBinder = (BtleService.LocalBinder) service;

        Log.i("AppLog", "Service Connected");

        //this.retrieveBoard(this.MW_MAC_ADDRESS);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Disconnect from the board
        this.disconnectBoard(this.MW_MAC_ADDRESS);
    }

    // connect to the Metawear board device
    private void retrieveBoard(String macAddr) {
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice = btManager.getAdapter().getRemoteDevice(macAddr);

        // create the MetaWear board object
        this.board = serviceBinder.getMetaWearBoard(remoteDevice);
        // connect to the board over bluetooth
        this.board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {

            @Override
            public Task<Route> then(Task<Void> task) throws Exception {
                Log.i("AppLog", "Connected to " + macAddr);

                accelerometer = board.getModule(Accelerometer.class);
                accelerometer.configure().odr(25f).commit(); //Set sampling frequency to 25Hz, or closest valid ODR
                return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                Log.i("AppLog", data.value(Acceleration.class).toString());
                            }
                        });
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {

            @Override
            public Void then(Task<Route> task) throws Exception {
                if (task.isFaulted()) {
                    Log.w("AppLog", "Failed to configure app", task.getError());
                } else {
                    Log.i("AppLog", "App configured");
                }
                return null;
            }
        });
    }

    /**
     * Function that is called when one of the buttons for changing status is called.
     * This will be transitioned once the data is driving the status change.
     * @param view
     */
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.statusOff_button:
                // change the status to OFF
                Log.i("AppLog", "Changing status to OFF");
                this.setMachineStatus(MachineStatus.OFF);
                this.setMachineStatusValue();
                break;
            case R.id.statusRunning_button:
                // change the status to running
                Log.i("AppLog", "Changing status to RUNNING");
                this.setMachineStatus(MachineStatus.RUNNING);
                this.setMachineStatusValue();
                break;
            case R.id.statusFinished_button:
                // change the status to finished
                Log.i("AppLog", "Changing status to FINISHED");
                this.setMachineStatus(MachineStatus.FINISHED);
                this.setMachineStatusValue();
                try {
                    this.notifications.get();
                } catch (Exception e) {
                    Log.w("AppLog", "ERROR_MAIN_THREAD");
                }
                break;
            default:
                // do nothing
                Log.i("AppLog", "ERROR: did not recognize action");
                break;
        }
    }

    // Disconnects from the Metawear board
    private void disconnectBoard(String macAddr) {
        this.board.disconnectAsync().continueWith(new Continuation<Void, Void>() {

            @Override
            public Void then(Task<Void> task) throws Exception {
                Log.i("AppLog", "Disconnected");
                return null;
            }
        });
    }

    // Updates the machineStatus variable with the current status
    private void setMachineStatus(MachineStatus status) {
        this.machineStatus = status;
        Log.i("AppLog", "New Machine Status: " + this.machineStatus);
    }

    // Updates the statusValue text view with the current value of the machineStatus variable
    private void setMachineStatusValue() {
        TextView statusText = (TextView)findViewById(R.id.statusValue);
        statusText.setText(this.machineStatus.getStringID());
    }


}

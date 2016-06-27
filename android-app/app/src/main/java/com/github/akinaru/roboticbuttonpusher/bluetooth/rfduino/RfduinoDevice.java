/****************************************************************************
 * This file is part of Bluetooth LE Analyzer.                              *
 * <p/>                                                                     *
 * Copyright (C) 2016  Bertrand Martel                                      *
 * <p/>                                                                     *
 * Foobar is free software: you can redistribute it and/or modify           *
 * it under the terms of the GNU General Public License as published by     *
 * the Free Software Foundation, either version 3 of the License, or        *
 * (at your option) any later version.                                      *
 * <p/>                                                                     *
 * Foobar is distributed in the hope that it will be useful,                *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of           *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
 * GNU General Public License for more details.                             *
 * <p/>                                                                     *
 * You should have received a copy of the GNU General Public License        *
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.          *
 */
package com.github.akinaru.roboticbuttonpusher.bluetooth.rfduino;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.github.akinaru.roboticbuttonpusher.bluetooth.connection.BluetoothDeviceAbstr;
import com.github.akinaru.roboticbuttonpusher.bluetooth.connection.IBluetoothDeviceConn;
import com.github.akinaru.roboticbuttonpusher.bluetooth.events.BluetoothEvents;
import com.github.akinaru.roboticbuttonpusher.bluetooth.listener.ICharacteristicListener;
import com.github.akinaru.roboticbuttonpusher.bluetooth.listener.IDeviceInitListener;
import com.github.akinaru.roboticbuttonpusher.bluetooth.listener.IPushListener;
import com.github.akinaru.roboticbuttonpusher.constant.DefaultKeys;
import com.github.akinaru.roboticbuttonpusher.constant.SharedPrefConst;
import com.github.akinaru.roboticbuttonpusher.inter.IAssociationStatusListener;
import com.github.akinaru.roboticbuttonpusher.inter.IDeassociateListener;
import com.github.akinaru.roboticbuttonpusher.inter.IInteractiveListener;
import com.github.akinaru.roboticbuttonpusher.inter.ITokenListener;
import com.github.akinaru.roboticbuttonpusher.model.BtnPusherInputTask;
import com.github.akinaru.roboticbuttonpusher.model.BtnPusherKeysType;
import com.github.akinaru.roboticbuttonpusher.model.ButtonPusherCmd;
import com.github.akinaru.roboticbuttonpusher.model.NotificationState;
import com.github.akinaru.roboticbuttonpusher.service.BtPusherService;
import com.github.akinaru.roboticbuttonpusher.utils.RandomGen;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * RFduino Bluetooth device management
 *
 * @author Bertrand Martel
 */
public class RfduinoDevice extends BluetoothDeviceAbstr implements IRfduinoDevice {

    private String TAG = RfduinoDevice.this.getClass().getName();

    public final static String RFDUINO_SERVICE = "00002220-0000-1000-8000-00805f9b34fb";
    public final static String RFDUINO_RECEIVE_CHARAC = "00002221-0000-1000-8000-00805f9b34fb";
    public final static String RFDUINO_SEND_CHARAC = "00002222-0000-1000-8000-00805f9b34fb";

    private ArrayList<IDeviceInitListener> initListenerList = new ArrayList<>();
    private byte[] data;
    private int chunkNum;

    private final static int SENDING_BUFFER_MAX_LENGTH = 18;

    private final static int TOP_MESSAGE_MAX_LENGTH = 28;

    private int sendIndex = 0;
    private int sendingNum = 0;
    private boolean remain = false;
    private byte[] bitmapData;

    private boolean stopProcessingBitmap = true;
    private long dateProcessBegin = 0;
    private int failCount = 0;
    private int frameNumToSend = 0;
    private NotificationState mState = NotificationState.SENDING;

    private ITokenListener mTokenListener;
    private IAssociationStatusListener mAssociationStatusListener;
    private IInteractiveListener mAssociationListener;
    private IInteractiveListener mPasswordListener;
    private IInteractiveListener mKeyListener;
    private IPushListener mPushListener;
    private IDeassociateListener mDeassociateListener;

    private byte[] mXorKey;
    private byte[] mAesKey;
    private byte[] mIv;

    private boolean init = false;
    private byte[] response;
    private int responseLength;
    private int responseIndex;

    private byte[] mExternalKey;
    private byte[] mExternalIv;

    private byte[] mToken;
    private String mPassword;
    private String oldPassword;
    private boolean generateDefaultKey = false;
    private BtnPusherInputTask mTask = BtnPusherInputTask.PUSH;
    private boolean sendMessage = false;

    /*
     * Creates a new pool of Thread objects for the download work queue
     */
    ExecutorService threadPool;

    /**
     * shared preference object.
     */
    private SharedPreferences sharedPref;

    private String topMessage;
    private String bottomMessage;


    private String generateXorKey() {
        return Base64.encodeToString(new RandomGen(32).nextString().getBytes(), Base64.DEFAULT);
    }

    private String getAesKey() {
        return Base64.encodeToString(DefaultKeys.AES_DEFAULT_KEY, Base64.DEFAULT);
    }

    private String getIv() {
        return Base64.encodeToString(DefaultKeys.IV_DEFAULT, Base64.DEFAULT);
    }

    /**
     * @param conn
     */
    @SuppressLint("NewApi")
    public RfduinoDevice(final IBluetoothDeviceConn conn) {
        super(conn);

        //shared preference
        sharedPref = conn.getManager().getService().getSharedPreferences(SharedPrefConst.PREFERENCES, Context.MODE_PRIVATE);
        mPassword = sharedPref.getString(SharedPrefConst.DEVICE_PASSWORD_FIELD, SharedPrefConst.DEFAULT_PASSWORD);

        String xorKey = sharedPref.getString(SharedPrefConst.XOR_KEY, generateXorKey());
        mXorKey = Base64.decode(xorKey, Base64.DEFAULT);

        String aesKey = sharedPref.getString(SharedPrefConst.AES_KEY, getAesKey());
        mAesKey = Base64.decode(aesKey, Base64.DEFAULT);

        String iv = sharedPref.getString(SharedPrefConst.IV, getIv());
        mIv = Base64.decode(iv, Base64.DEFAULT);

        Log.i(TAG, "mXorKey : " + xorKey + " : " + bytesToHex(mXorKey));

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(SharedPrefConst.XOR_KEY, Base64.encodeToString(mXorKey, Base64.DEFAULT));
        editor.commit();

        threadPool = Executors.newFixedThreadPool(1);

        setCharacteristicListener(new ICharacteristicListener() {

            @Override
            public void onCharacteristicReadReceived(BluetoothGattCharacteristic charac) {
                Log.v(TAG, "onCharacteristicReadReceived");
            }

            @Override
            public void onCharacteristicChangeReceived(BluetoothGattCharacteristic charac) {

                Log.i(TAG, "receive something : " + charac.getUuid().toString() + " " + charac.getValue().length + " " + charac.getValue()[0]);
                Log.i(TAG, "=> " + bytesToHex(charac.getValue()));

                switch (mState) {
                    case SENDING:
                        Log.i(TAG, "charac.getValue()[0] : " + charac.getValue()[0]);
                        ButtonPusherCmd cmd = ButtonPusherCmd.getValue((charac.getValue()[0] & 0xFF));

                        Log.i(TAG, "test : " + cmd);

                        switch (cmd) {
                            case COMMAND_GET_TOKEN:

                                byte[] data = new byte[18];
                                System.arraycopy(charac.getValue(), 0, data, 0, 18);
                                Log.i(TAG, "receive charac string : " + new String(data));
                                String value = new String(data);

                                Log.i(TAG, "receive COMMAND_GET_TOKEN notification : " + value.substring(2));
                                if (mTokenListener != null) {
                                    mTokenListener.onTokenReceived(hexStringToByteArray(value.substring(2)));
                                }
                                break;
                            case COMMAND_ASSOCIATION_STATUS:

                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mAssociationStatusListener != null) {
                                            mAssociationStatusListener.onStatusFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mAssociationStatusListener != null) {
                                            mAssociationStatusListener.onStatusSuccess();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_FAILURE:
                                if (charac.getValue().length > 0) {
                                    Log.e(TAG, "command failure");
                                }
                                break;
                            case COMMAND_DEASSOCIATE:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mDeassociateListener != null) {
                                            mDeassociateListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mDeassociateListener != null) {
                                            mDeassociateListener.onSuccess();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_SET_PASSWORD:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mPasswordListener != null) {
                                            mPasswordListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mPasswordListener != null) {
                                            mPasswordListener.onSuccess();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 2) {
                                        if (mPasswordListener != null) {
                                            mPasswordListener.onUserActionRequired();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_SET_KEY:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mKeyListener != null) {
                                            mKeyListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mKeyListener != null) {
                                            mKeyListener.onSuccess();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 2) {
                                        if (mKeyListener != null) {
                                            mKeyListener.onUserActionRequired();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_ASSOCIATE:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mAssociationListener != null) {
                                            mAssociationListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mAssociationListener != null) {
                                            mAssociationListener.onSuccess();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 2) {
                                        if (mAssociationListener != null) {
                                            mAssociationListener.onUserActionRequired();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_SET_KEYS_RESPONSE: {
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mKeyListener != null) {
                                            mKeyListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mKeyListener != null) {
                                            mKeyListener.onSuccess();
                                        }
                                    }
                                }
                                break;
                            }
                            case COMMAND_SET_PASSWORD_RESPONSE: {
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mPasswordListener != null) {
                                            mPasswordListener.onFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mPasswordListener != null) {
                                            mPasswordListener.onSuccess();
                                        }
                                    }
                                }
                                break;
                            }
                            case COMMAND_ASSOCIATE_RESPONSE:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 2) {

                                        Log.i(TAG, "receiving association response");
                                        mState = NotificationState.RECEIVING;
                                        responseIndex = 0;
                                        Log.i(TAG, new String(charac.getValue()).replaceAll("[^\\x20-\\x7e]", ""));
                                        String[] dataStr = new String(charac.getValue()).replaceAll("[^\\x20-\\x7e]", "").split(":");
                                        if (dataStr.length > 2) {
                                            responseLength = Integer.parseInt(dataStr[2]);
                                            response = new byte[responseLength];
                                            Log.i(TAG, "receive length : " + responseLength);
                                            conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_RECEIVE_KEYS.ordinal()}, null);
                                        }
                                    }
                                }
                                break;
                            case COMMAND_MESSAGE_BOTTOM:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mPushListener != null) {
                                            mPushListener.onPushFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mPushListener != null) {
                                            mPushListener.onPushSuccess();
                                        }
                                    }
                                }
                                break;
                            case COMMAND_MESSAGE_TOP:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mPushListener != null) {
                                            mPushListener.onPushFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mPushListener != null) {

                                            sendMessage = false;

                                            threadPool.execute(new Runnable() {
                                                @Override
                                                public void run() {
                                                    sendCommand(BtnPusherInputTask.MESSAGE);
                                                }
                                            });
                                        }
                                    }
                                }
                                break;
                            case COMMAND_PUSH:
                                if (charac.getValue().length > 2) {

                                    if (((charac.getValue()[2] & 0xFF)) == 1) {
                                        if (mPushListener != null) {
                                            mPushListener.onPushFailure();
                                        }
                                    } else if (((charac.getValue()[2] & 0xFF)) == 0) {
                                        if (mPushListener != null) {
                                            mPushListener.onPushSuccess();
                                        }
                                    }
                                }
                                break;
                            default:
                                break;
                        }
                        break;
                    case RECEIVING:
                        Log.i(TAG, "in RECEIVING state : " + charac.getValue().length);
                        for (int i = 0; i < charac.getValue().length; i++) {
                            response[responseIndex++] = charac.getValue()[i];
                        }
                        Log.i(TAG, "responseIndex : " + responseIndex);
                        if (responseIndex == responseLength) {
                            mState = NotificationState.SENDING;
                            Log.i(TAG, "received everything");
                            byte[] encodedKey = new byte[64];
                            byte[] encodedIv = new byte[64];

                            System.arraycopy(response, 0, encodedKey, 0, 64);
                            System.arraycopy(response, 64, encodedIv, 0, 64);

                            Log.i(TAG, "key : " + bytesToHex(mExternalKey));
                            Log.i(TAG, "iv  : " + bytesToHex(mExternalIv));

                            Log.i(TAG, "encoded key : " + bytesToHex(encodedKey));
                            Log.i(TAG, "encoded iv  : " + bytesToHex(encodedIv));

                            byte[] decodedKey = BtPusherService.decrypt(encodedKey, 64, mExternalKey, Arrays.copyOf(mExternalIv, mExternalIv.length));
                            byte[] decodedIv = BtPusherService.decrypt(encodedIv, 64, mExternalKey, Arrays.copyOf(mExternalIv, mExternalIv.length));

                            Log.i(TAG, "decoded key : " + bytesToHex(decodedKey));
                            Log.i(TAG, "decoded iv  : " + bytesToHex(decodedIv));

                            for (int i = 0; i < 32; i++) {
                                mAesKey[i] = decodedKey[i];
                            }
                            for (int i = 0; i < 16; i++) {
                                mIv[i] = decodedIv[i];
                            }

                            SharedPreferences.Editor editor = sharedPref.edit();
                            editor.putString(SharedPrefConst.AES_KEY, Base64.encodeToString(mAesKey, Base64.DEFAULT));
                            editor.putString(SharedPrefConst.IV, Base64.encodeToString(mIv, Base64.DEFAULT));
                            editor.commit();

                            if (mAssociationListener != null) {
                                mAssociationListener.onSuccess();
                            }
                        } else {
                            Log.i(TAG, "requesting next batch");
                            conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_RECEIVE_KEYS.ordinal()}, null);
                        }
                        break;
                }
            }

            @Override
            public void onCharacteristicWriteReceived(BluetoothGattCharacteristic charac) {
                Log.v(TAG, "onCharacteristicWriteReceived : " + new String(charac.getValue()));
            }
        });
    }

    private void clearBimapInfo() {
        sendingNum = 0;
        remain = false;
        sendIndex = 0;
        bitmapData = new byte[]{};
        stopProcessingBitmap = true;
    }

    @Override
    public void init() {

        Log.v(TAG, "initializing RFduino");

        conn.enableDisableNotification(UUID.fromString(RFDUINO_SERVICE), UUID.fromString(RFDUINO_RECEIVE_CHARAC), true);
        conn.enableGattNotifications(RFDUINO_SERVICE, RFDUINO_RECEIVE_CHARAC);

        for (int i = 0; i < initListenerList.size(); i++) {
            initListenerList.get(i).onInit();
        }
    }

    @Override
    public boolean isInit() {
        return init;
    }

    @Override
    public void addInitListener(IDeviceInitListener listener) {
        initListenerList.add(listener);
    }

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private byte[] buildAssociationStatusRequest(byte[] token) {

        if (token.length < 20) {
            Log.i(TAG, "token length : " + token.length);
            byte[] xored = new byte[8];

            int i = 0;
            for (byte element : token) {
                Log.i(TAG, (mXorKey[i] & 0xFF) + " ^ " + (element & 0xFF));
                xored[i] = (byte) (mXorKey[i++] ^ element);
                Log.i(TAG, "xored[i] : " + (xored[i - 1] & 0xFF));
            }

            byte[] data = new byte[16];
            byte[] serial = hexStringToByteArray(Build.SERIAL);

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }

            for (int j = 0; j < 8; j++) {
                data[j + 8] = xored[j];
            }

            Log.i(TAG, "output : " + bytesToHex(data));

            return BtPusherService.encrypt(data, data.length, mAesKey, Arrays.copyOf(mIv, mIv.length));

        } else {
            Log.e(TAG, "token length cant be <20");
        }
        return null;
    }

    private byte[] buildMessageRequest(byte[] token, byte[] password, String message) {

        if (token.length < 20 && (password.length <= 20)) {

            byte[] decodedVal = new byte[28];
            System.arraycopy(token, 0, decodedVal, 0, 8);
            Log.i(TAG, "complete1 : " + bytesToHex(decodedVal));
            for (int i = 0; i < password.length; i++) {
                decodedVal[i + 8] = password[i];
            }
            Log.i(TAG, "complete2 : " + bytesToHex(decodedVal));
            if (password.length < 20) {
                for (int i = password.length; i < 20; i++) {
                    decodedVal[i + 8] = 0;
                }
            }
            Log.i(TAG, "complete3 : " + bytesToHex(decodedVal));

            Log.i(TAG, "token length : " + token.length);
            byte[] xored = new byte[28];

            int i = 0;
            for (byte element : decodedVal) {
                Log.i(TAG, (mXorKey[i] & 0xFF) + " ^ " + (element & 0xFF));
                xored[i] = (byte) (mXorKey[i++] ^ element);
                Log.i(TAG, "xored[i] : " + (xored[i - 1] & 0xFF));
            }

            byte[] data = new byte[36 + TOP_MESSAGE_MAX_LENGTH];
            byte[] serial = hexStringToByteArray(Build.SERIAL);

            Log.i(TAG, "device idd : " + bytesToHex(serial) + " => " + Build.SERIAL);

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }

            for (int j = 0; j < 28; j++) {
                data[j + 8] = xored[j];
            }

            byte[] topMessageBa = message.getBytes();

            for (int j = 36; j < (36 + TOP_MESSAGE_MAX_LENGTH); j++) {
                if (topMessageBa.length > (j - 36)) {
                    data[j] = topMessageBa[j - 36];
                } else {
                    data[j] = 0;
                }
            }

            Log.i(TAG, "output : " + bytesToHex(data));

            return BtPusherService.encrypt(data, data.length, mAesKey, Arrays.copyOf(mIv, mIv.length));

        } else {
            Log.e(TAG, "token length cant be <20");
        }
        return null;
    }

    private byte[] buildPushRequest(byte[] token, byte[] password) {

        if (token.length < 20 && (password.length <= 20)) {

            byte[] decodedVal = new byte[28];
            System.arraycopy(token, 0, decodedVal, 0, 8);
            Log.i(TAG, "complete1 : " + bytesToHex(decodedVal));
            for (int i = 0; i < password.length; i++) {
                decodedVal[i + 8] = password[i];
            }
            Log.i(TAG, "complete2 : " + bytesToHex(decodedVal));
            if (password.length < 20) {
                for (int i = password.length; i < 20; i++) {
                    decodedVal[i + 8] = 0;
                }
            }
            Log.i(TAG, "complete3 : " + bytesToHex(decodedVal));

            Log.i(TAG, "token length : " + token.length);
            byte[] xored = new byte[28];

            int i = 0;
            for (byte element : decodedVal) {
                Log.i(TAG, (mXorKey[i] & 0xFF) + " ^ " + (element & 0xFF));
                xored[i] = (byte) (mXorKey[i++] ^ element);
                Log.i(TAG, "xored[i] : " + (xored[i - 1] & 0xFF));
            }

            byte[] data = new byte[36];
            byte[] serial = hexStringToByteArray(Build.SERIAL);

            Log.i(TAG, "device idd : " + bytesToHex(serial) + " => " + Build.SERIAL);

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }

            for (int j = 0; j < 28; j++) {
                data[j + 8] = xored[j];
            }

            Log.i(TAG, "output : " + bytesToHex(data));

            return BtPusherService.encrypt(data, data.length, mAesKey, Arrays.copyOf(mIv, mIv.length));

        } else {
            Log.e(TAG, "token length cant be <20");
        }
        return null;
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    public void setKeys(String password, BtnPusherKeysType keysType) {
        switch (keysType) {
            case DEFAULT:
                generateDefaultKey = true;
                sendCommand(BtnPusherInputTask.KEYS_DEFAULT);
                break;
            case GENERATED:
                generateDefaultKey = false;
                sendCommand(BtnPusherInputTask.KEYS_GENERATED);
                break;
        }
    }

    @Override
    public void disassociate() {
        sendCommand(BtnPusherInputTask.DISASSOCIATE);
    }

    @Override
    public void setMessage(String top, String bottom) {
        this.topMessage = top;
        this.bottomMessage = bottom;
        sendMessage = true;
        sendCommand(BtnPusherInputTask.MESSAGE);
    }

    @Override
    public void setPassword(String oldPass) {
        oldPassword = oldPass;
        sendCommand(BtnPusherInputTask.PASSWORD);
    }

    private void sendCommitedPasswordResponse(String code, byte[] token) {

        if (code.toUpperCase().matches("[0123456789ABCDEF]*") && (code.length() % 2 == 0)) {

            byte[] codeBa = hexStringToByteArray(code.toUpperCase());
            byte[] serial = hexStringToByteArray(Build.SERIAL);
            byte[] data = new byte[36];

            byte[] decodedVal = new byte[28];
            System.arraycopy(token, 0, decodedVal, 0, 8);
            Log.i(TAG, "complete1 : " + bytesToHex(decodedVal));
            for (int i = 0; i < mPassword.getBytes().length; i++) {
                decodedVal[i + 8] = mPassword.getBytes()[i];
            }
            Log.i(TAG, "complete2 : " + bytesToHex(decodedVal));
            if (mPassword.getBytes().length < 20) {
                for (int i = mPassword.getBytes().length; i < 20; i++) {
                    decodedVal[i + 8] = 0;
                }
            }
            Log.i(TAG, "complete3 : " + bytesToHex(decodedVal));

            Log.i(TAG, "token length : " + token.length);
            byte[] xored = new byte[28];

            int i = 0;
            for (byte element : decodedVal) {
                Log.i(TAG, (mXorKey[i] & 0xFF) + " ^ " + (element & 0xFF));
                xored[i] = (byte) (mXorKey[i++] ^ element);
                Log.i(TAG, "xored[i] : " + (xored[i - 1] & 0xFF));
            }

            Log.i(TAG, "device idd : " + bytesToHex(serial) + " => " + Build.SERIAL);

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }

            for (int j = 0; j < 28; j++) {
                data[j + 8] = xored[j];
            }

            Log.i(TAG, "code    : " + bytesToHex(codeBa));
            Log.i(TAG, "Build.SERIAL : " + Build.SERIAL + " serial  : " + bytesToHex(serial));
            mExternalKey = BtPusherService.generatekey(codeBa);
            mExternalIv = BtPusherService.generateiv(codeBa);

            Log.i(TAG, "key : " + bytesToHex(mExternalKey));
            Log.i(TAG, "iv  : " + bytesToHex(mExternalIv));

            sendBitmap((byte) ButtonPusherCmd.COMMAND_SET_PASSWORD_RESPONSE.ordinal(), BtPusherService.encrypt(data, data.length, mExternalKey, Arrays.copyOf(mExternalIv, mExternalIv.length)));

        } else

        {
            Log.e(TAG, "error code is invalid");
            mPasswordListener.onFailure();
        }

    }

    private void sendCommitedKeysResponse(String code, byte[] token, ButtonPusherCmd cmd) throws NoSuchAlgorithmException {

        if (code.toUpperCase().matches("[0123456789ABCDEF]*") && (code.length() % 2 == 0)) {

            byte[] codeBa = hexStringToByteArray(code.toUpperCase());
            byte[] serial = hexStringToByteArray(Build.SERIAL);
            byte[] data = new byte[64];

            byte[] decodedVal = new byte[56];
            System.arraycopy(token, 0, decodedVal, 0, 8);
            Log.i(TAG, "complete1 : " + bytesToHex(decodedVal));

            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256); // for example
            SecretKey secretKey = keyGen.generateKey();
            byte[] aesKey = secretKey.getEncoded();
            Log.i(TAG, "secretKey => " + secretKey.getEncoded().length);


            Log.i(TAG, "complete2 : " + bytesToHex(decodedVal));
            for (int i = 8; i < 40; i++) {
                decodedVal[i + 8] = aesKey[i - 8];
            }

            byte[] iv = new byte[16];
            new Random().nextBytes(iv);

            for (int i = 40; i < 56; i++) {
                decodedVal[i] = iv[i - 40];
            }

            Log.i(TAG, "complete3 : " + bytesToHex(decodedVal));

            Log.i(TAG, "token length : " + token.length);
            byte[] xored1 = new byte[32];
            byte[] xored2 = new byte[32];

            for (byte element = 0; element < 32; element++) {
                xored1[element] = (byte) (mXorKey[element] ^ decodedVal[element]);
            }
            for (byte element = 32; element < 56; element++) {
                xored2[element - 32] = (byte) (mXorKey[element - 32] ^ decodedVal[element]);
            }

            Log.i(TAG, "device idd : " + bytesToHex(serial) + " => " + Build.SERIAL);

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }

            for (int j = 8; j < 40; j++) {
                data[j] = xored1[j - 8];
            }
            for (int j = 40; j < 64; j++) {
                data[j] = xored2[j - 40];
            }

            Log.i(TAG, "code    : " + bytesToHex(codeBa));
            Log.i(TAG, "Build.SERIAL : " + Build.SERIAL + " serial  : " + bytesToHex(serial));
            mExternalKey = BtPusherService.generatekey(codeBa);
            mExternalIv = BtPusherService.generateiv(codeBa);

            Log.i(TAG, "key : " + bytesToHex(mExternalKey));
            Log.i(TAG, "iv  : " + bytesToHex(mExternalIv));

            sendBitmap((byte) ButtonPusherCmd.COMMAND_SET_KEYS_RESPONSE.ordinal(), BtPusherService.encrypt(data, data.length, mExternalKey, Arrays.copyOf(mExternalIv, mExternalIv.length)));

        } else {
            Log.e(TAG, "error code is invalid");
            mPasswordListener.onFailure();
        }
    }

    private void sendUserCommittedResponse(String code, byte[] token, ButtonPusherCmd cmd) {

        if (code.toUpperCase().matches("[0123456789ABCDEF]*") && (code.length() % 2 == 0)) {

            byte[] codeBa = hexStringToByteArray(code.toUpperCase());
            byte[] serial = hexStringToByteArray(Build.SERIAL);
            byte[] data = new byte[8 + 32 + 8];

            for (int j = 0; j < 8; j++) {
                data[j] = serial[j];
            }
            for (int j = 0; j < 32; j++) {
                data[j + 8] = mXorKey[j];
            }
            for (int j = 0; j < 8; j++) {
                data[j + 40] = token[j];
            }
            Log.i(TAG, "code    : " + bytesToHex(codeBa));
            Log.i(TAG, "Build.SERIAL : " + Build.SERIAL + " serial  : " + bytesToHex(serial));
            mExternalKey = BtPusherService.generatekey(codeBa);
            mExternalIv = BtPusherService.generateiv(codeBa);

            Log.i(TAG, "key : " + bytesToHex(mExternalKey));
            Log.i(TAG, "iv  : " + bytesToHex(mExternalIv));

            sendBitmap((byte) cmd.ordinal(), BtPusherService.encrypt(data, data.length, mExternalKey, Arrays.copyOf(mExternalIv, mExternalIv.length)));

        } else {
            Log.e(TAG, "error code is invalid");
            mAssociationListener.onFailure();
        }
    }

    private void sendCommand(final BtnPusherInputTask task) {

        mState = NotificationState.SENDING;
        mTask = task;
        mAssociationStatusListener = new IAssociationStatusListener() {
            @Override
            public void onStatusSuccess() {
                Log.i(TAG, "You are already associated to this device");

                mTokenListener = new ITokenListener() {
                    @Override
                    public void onTokenReceived(final byte[] token) {

                        mToken = token;
                        Log.i(TAG, "token received sending association status request");

                        switch (task) {
                            case DISASSOCIATE:
                                mDeassociateListener = new IDeassociateListener() {
                                    @Override
                                    public void onSuccess() {
                                        Log.i(TAG, "deassociate success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_DISASSOCIATE_SUCCESS, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onFailure() {
                                        Log.i(TAG, "deassociate failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_DISASSOCIATE_FAILURE, new ArrayList<String>());
                                    }
                                };
                                break;
                            case PASSWORD:
                                mPasswordListener = new IInteractiveListener() {
                                    @Override
                                    public void onSuccess() {
                                        Log.i(TAG, "set password success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_PASSWORD_SUCCESS, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onFailure() {
                                        Log.e(TAG, "user action failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_PASSWORD_FAILURE, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionRequired() {
                                        Log.i(TAG, "user action required");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_USER_ACTION_REQUIRED, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionCommitted(final String code) {

                                        mTokenListener = new ITokenListener() {
                                            @Override
                                            public void onTokenReceived(byte[] token) {
                                                mToken = token;
                                                Log.i(TAG, "user action committed : " + code);
                                                sendCommitedPasswordResponse(code, token);
                                            }
                                        };
                                        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
                                    }
                                };
                                break;
                            case KEYS_DEFAULT:
                            case KEYS_GENERATED:
                                mKeyListener = new IInteractiveListener() {
                                    @Override
                                    public void onSuccess() {
                                        Log.i(TAG, "set key success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_SUCCESS, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onFailure() {
                                        Log.e(TAG, "set key failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_FAILURE, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionRequired() {
                                        Log.i(TAG, "user action required");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_USER_ACTION_REQUIRED, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionCommitted(final String code) {

                                        mTokenListener = new ITokenListener() {
                                            @Override
                                            public void onTokenReceived(byte[] token) {
                                                mToken = token;
                                                Log.i(TAG, "user action committed : " + code);
                                                try {
                                                    sendCommitedKeysResponse(code, token, ButtonPusherCmd.COMMAND_SET_KEYS_RESPONSE);
                                                } catch (NoSuchAlgorithmException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        };
                                        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
                                    }
                                };
                                break;
                        }
                        mPushListener = new IPushListener() {

                            @Override
                            public void onPushFailure() {
                                switch (task) {
                                    case PUSH:
                                        Log.i(TAG, "push failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_PUSH_FAILURE, new ArrayList<String>());
                                        break;
                                    case MESSAGE:
                                        Log.i(TAG, "set message failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_MESSAGE_FAILURE, new ArrayList<String>());
                                        break;
                                    case PASSWORD:
                                        Log.i(TAG, "set password failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_PASSWORD_FAILURE, new ArrayList<String>());
                                        break;
                                    case KEYS_DEFAULT:
                                        Log.i(TAG, "set key default failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_FAILURE, new ArrayList<String>());
                                        break;
                                    case KEYS_GENERATED:
                                        Log.i(TAG, "set key generated failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_FAILURE, new ArrayList<String>());
                                        break;
                                    case DISASSOCIATE:
                                        Log.i(TAG, "disassociate failure");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_DISASSOCIATE_FAILURE, new ArrayList<String>());
                                        break;
                                }
                            }

                            @Override
                            public void onPushSuccess() {

                                switch (task) {
                                    case PUSH:
                                        Log.i(TAG, "push success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_PUSH_SUCCESS, new ArrayList<String>());
                                        break;
                                    case MESSAGE:
                                        Log.i(TAG, "set message top success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_MESSAGE_SUCCESS, new ArrayList<String>());
                                        break;
                                    case PASSWORD:
                                        Log.i(TAG, "set password success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_PASSWORD_SUCCESS, new ArrayList<String>());
                                        break;
                                    case KEYS_DEFAULT:
                                        Log.i(TAG, "set key default success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_SUCCESS, new ArrayList<String>());
                                        break;
                                    case KEYS_GENERATED:
                                        Log.i(TAG, "set key generated success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_SUCCESS, new ArrayList<String>());
                                        break;
                                    case DISASSOCIATE:
                                        Log.i(TAG, "disassociate success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_DISASSOCIATE_SUCCESS, new ArrayList<String>());
                                        break;
                                }
                            }
                        };

                        switch (task) {
                            case PUSH:
                                sendBitmap((byte) ButtonPusherCmd.COMMAND_PUSH.ordinal(), buildPushRequest(mToken, mPassword.getBytes()));
                                break;
                            case PASSWORD:
                                Log.i(TAG, "mPassword => " + mPassword);
                                sendBitmap((byte) ButtonPusherCmd.COMMAND_SET_PASSWORD.ordinal(), buildPushRequest(mToken, oldPassword.getBytes()));
                                break;
                            case KEYS_DEFAULT:
                                sendBitmap((byte) ButtonPusherCmd.COMMAND_SET_KEY.ordinal(), buildPushRequest(mToken, mPassword.getBytes()));
                                break;
                            case KEYS_GENERATED:
                                sendBitmap((byte) ButtonPusherCmd.COMMAND_SET_KEY.ordinal(), buildPushRequest(mToken, mPassword.getBytes()));
                                break;
                            case MESSAGE:
                                if (sendMessage) {
                                    sendBitmap((byte) ButtonPusherCmd.COMMAND_MESSAGE_TOP.ordinal(), buildMessageRequest(mToken, mPassword.getBytes(), topMessage));
                                } else {
                                    sendBitmap((byte) ButtonPusherCmd.COMMAND_MESSAGE_BOTTOM.ordinal(), buildMessageRequest(mToken, mPassword.getBytes(), bottomMessage));
                                }
                                break;
                            case DISASSOCIATE:
                                sendBitmap((byte) ButtonPusherCmd.COMMAND_DEASSOCIATE.ordinal(), buildPushRequest(mToken, mPassword.getBytes()));
                                break;
                        }
                    }
                };

                conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
            }

            @Override
            public void onStatusFailure() {
                Log.e(TAG, "You are not yet associated with this device. Associating ...");

                switch (task) {
                    case PUSH:
                        mTokenListener = new ITokenListener() {

                            @Override
                            public void onTokenReceived(final byte[] token) {

                                Log.i(TAG, "token received sending associate request");

                                mAssociationListener = new IInteractiveListener() {
                                    @Override
                                    public void onSuccess() {
                                        Log.i(TAG, "association success");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_ASSOCIATION_SUCCESS, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onFailure() {
                                        Log.e(TAG, "association failure");
                                        conn.disconnect();
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_ASSOCIATION_FAILURE, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionRequired() {
                                        Log.i(TAG, "user action required");
                                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_USER_ACTION_REQUIRED, new ArrayList<String>());
                                    }

                                    @Override
                                    public void onUserActionCommitted(String code) {

                                        Log.i(TAG, "user action committed : " + code);
                                        sendUserCommittedResponse(code, token, ButtonPusherCmd.COMMAND_ASSOCIATE_RESPONSE);
                                    }
                                };

                                conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_ASSOCIATE.ordinal()}, null);
                            }
                        };

                        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);

                    case PASSWORD:
                        Log.e(TAG, "set password failure");
                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_PASSWORD_FAILURE, new ArrayList<String>());
                        break;
                    case KEYS_DEFAULT:
                        Log.e(TAG, "set keys default failure");
                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_FAILURE, new ArrayList<String>());
                        break;
                    case KEYS_GENERATED:
                        Log.e(TAG, "set keys generated failure");
                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_SET_KEYS_FAILURE, new ArrayList<String>());
                        break;
                    case DISASSOCIATE:
                        Log.e(TAG, "disassociate failure");
                        conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_DISASSOCIATE_FAILURE, new ArrayList<String>());
                        break;
                    default:
                        break;
                }
            }
        };

        mTokenListener = new ITokenListener() {
            @Override
            public void onTokenReceived(byte[] token) {
                mToken = token;
                Log.i(TAG, "token received sending association status request");
                sendBitmap((byte) ButtonPusherCmd.COMMAND_ASSOCIATION_STATUS.ordinal(), buildAssociationStatusRequest(mToken));
            }
        };

        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
    }

    @Override
    public void sendPush(String password) {

        mState = NotificationState.SENDING;
        mTask = BtnPusherInputTask.PUSH;
        mAssociationStatusListener = new IAssociationStatusListener() {
            @Override
            public void onStatusSuccess() {
                Log.i(TAG, "You are already associated to this device");

                mTokenListener = new ITokenListener() {
                    @Override
                    public void onTokenReceived(byte[] token) {

                        mToken = token;
                        Log.i(TAG, "token received sending association status request");

                        mPushListener = new IPushListener() {
                            @Override
                            public void onPushFailure() {
                                Log.i(TAG, "push failure");
                                conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_PUSH_FAILURE, new ArrayList<String>());
                            }

                            @Override
                            public void onPushSuccess() {
                                Log.e(TAG, "push success");
                                conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_PUSH_SUCCESS, new ArrayList<String>());
                            }
                        };
                        sendBitmap((byte) ButtonPusherCmd.COMMAND_PUSH.ordinal(), buildPushRequest(mToken, mPassword.getBytes()));
                    }
                };

                conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
            }

            @Override
            public void onStatusFailure() {
                Log.e(TAG, "You are not yet associated with this device. Associating ...");

                mTokenListener = new ITokenListener() {

                    @Override
                    public void onTokenReceived(final byte[] token) {

                        Log.i(TAG, "token received sending associate request");

                        mAssociationListener = new IInteractiveListener() {
                            @Override
                            public void onSuccess() {
                                Log.i(TAG, "association success");
                                conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_ASSOCIATION_SUCCESS, new ArrayList<String>());
                            }

                            @Override
                            public void onFailure() {
                                Log.e(TAG, "association failure");
                                conn.disconnect();
                                conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_ASSOCIATION_FAILURE, new ArrayList<String>());
                            }

                            @Override
                            public void onUserActionRequired() {
                                Log.i(TAG, "user action required");
                                conn.getManager().broadcastUpdateStringList(BluetoothEvents.BT_EVENT_DEVICE_USER_ACTION_REQUIRED, new ArrayList<String>());
                            }

                            @Override
                            public void onUserActionCommitted(String code) {
                                Log.i(TAG, "user action committed : " + code);
                                sendUserCommittedResponse(code, token, ButtonPusherCmd.COMMAND_ASSOCIATE_RESPONSE);
                            }
                        };

                        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_ASSOCIATE.ordinal()}, null);
                    }
                };

                conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
            }
        };

        mTokenListener = new ITokenListener() {
            @Override
            public void onTokenReceived(byte[] token) {
                mToken = token;
                Log.i(TAG, "token received sending association status request");
                sendBitmap((byte) ButtonPusherCmd.COMMAND_ASSOCIATION_STATUS.ordinal(), buildAssociationStatusRequest(mToken));
            }
        };

        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{(byte) ButtonPusherCmd.COMMAND_GET_TOKEN.ordinal()}, null);
    }

    @Override
    public void sendAssociationCode(String code) {
        switch (mTask) {
            case PUSH:
                if (mAssociationListener != null) {
                    mAssociationListener.onUserActionCommitted(code);
                }
                break;
            case PASSWORD:
                if (mPasswordListener != null) {
                    mPasswordListener.onUserActionCommitted(code);
                }
                break;
            case KEYS_DEFAULT:
            case KEYS_GENERATED:
                if (mKeyListener != null) {
                    mKeyListener.onUserActionCommitted(code);
                }
                break;
        }
    }

    private void sendBitmapSequence() {

        if (!stopProcessingBitmap) {

            if (sendIndex != sendingNum) {

                //Log.i(TAG, "index : " + sendIndex + " from " + (sendIndex * SENDING_BUFFER_MAX_LENGTH) + " to " + (sendIndex * SENDING_BUFFER_MAX_LENGTH + SENDING_BUFFER_MAX_LENGTH) + " with length of " + bitmapData.length);
                byte[] data = Arrays.copyOfRange(bitmapData, sendIndex * SENDING_BUFFER_MAX_LENGTH, sendIndex * SENDING_BUFFER_MAX_LENGTH + SENDING_BUFFER_MAX_LENGTH);
                sendIndex++;
                final long dateBegin = new Date().getTime();

                conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, data, new IPushListener() {
                    @Override
                    public void onPushFailure() {

                        Log.e(TAG, "error happenend during transmission. Retrying");
 /*
                        sendIndex--;
                        failCount++;
                        sendBitmapSequence();
                        */
                    }

                    @Override
                    public void onPushSuccess() {
                        /*
                        long dateEnd = new Date().getTime();
                        float timeSpan = (dateEnd - dateBegin) / 1000f;
                        float speed = (SENDING_BUFFER_MAX_LENGTH * 8) / timeSpan;
                        Log.i(TAG, "current speed : " + speed + "bps");
                        */
                    }
                });
                frameNumToSend--;

                if (frameNumToSend != 0) {
                    sendBitmapSequence();
                }
            } else {

                if (remain) {

                    int remainNum = bitmapData.length % SENDING_BUFFER_MAX_LENGTH;
                    //Log.i(TAG, "index : " + sendingNum + " from " + (sendingNum * SENDING_BUFFER_MAX_LENGTH) + " to " + (sendingNum * SENDING_BUFFER_MAX_LENGTH + remainNum) + " with length of " + bitmapData.length);
                    byte[] data = Arrays.copyOfRange(bitmapData, sendingNum * SENDING_BUFFER_MAX_LENGTH, sendingNum * SENDING_BUFFER_MAX_LENGTH + remainNum);

                    conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, data, new IPushListener() {
                        @Override
                        public void onPushFailure() {
                            /*
                            Log.e(TAG, "error happenend during transmission. Retrying");
                            failCount++;
                            sendBitmapSequence();
                            */
                        }

                        @Override
                        public void onPushSuccess() {
                            /*
                            Log.i(TAG, "completly finished in " + (new Date().getTime() - dateProcessBegin) + "ms - fail : " + failCount + " packet count : " + sendingNum);
                            clearBimapInfo();
                            */
                        }
                    });
                    Log.i(TAG, "completly finished in " + (new Date().getTime() - dateProcessBegin) + "ms - fail : " + failCount + " packet count : " + sendingNum);
                }
            }
        } else {
            Log.i(TAG, "stop processing bitmap");
        }
    }

    private void sendBitmap(byte cmd, final byte[] bitmapData) {

        mState = NotificationState.SENDING;

        Log.i(TAG, "send bitmap with length : " + bitmapData.length);

        sendingNum = bitmapData.length / SENDING_BUFFER_MAX_LENGTH;
        remain = false;
        if ((bitmapData.length % SENDING_BUFFER_MAX_LENGTH) != 0) {
            remain = true;
        }
        sendIndex = 0;
        RfduinoDevice.this.bitmapData = bitmapData;
        stopProcessingBitmap = false;
        dateProcessBegin = new Date().getTime();
        failCount = 0;

        //send cmd + length
        conn.writeCharacteristic(RFDUINO_SERVICE, RFDUINO_SEND_CHARAC, new byte[]{cmd, (byte) (bitmapData.length >> 8), (byte) bitmapData.length}, new IPushListener() {
            @Override
            public void onPushFailure() {
                Log.e(TAG, "error happenend setting bitmap length");
            }

            @Override
            public void onPushSuccess() {
                Log.i(TAG, "set bitmap length successfull");

            }
        });
        frameNumToSend = 127;
        sendBitmapSequence();
    }
}
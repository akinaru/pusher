/**********************************************************************************
 * This file is part of Pusher.                                                    *
 * <p/>                                                                            *
 * Copyright (C) 2016  Bertrand Martel                                             *
 * <p/>                                                                            *
 * Pusher is free software: you can redistribute it and/or modify                  *
 * it under the terms of the GNU General Public License as published by            *
 * the Free Software Foundation, either version 3 of the License, or               *
 * (at your option) any later version.                                             *
 * <p/>                                                                            *
 * Pusher is distributed in the hope that it will be useful,                       *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                  *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                   *
 * GNU General Public License for more details.                                    *
 * <p/>                                                                            *
 * You should have received a copy of the GNU General Public License               *
 * along with Pusher. If not, see <http://www.gnu.org/licenses/>.                  *
 */
package com.github.akinaru.roboticbuttonpusher.dialog;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import com.github.akinaru.roboticbuttonpusher.R;
import com.github.akinaru.roboticbuttonpusher.inter.IButtonPusher;

/**
 * Max packet count dialog
 *
 * @author Bertrand Martel
 */
public class DeviceMessageDialog extends AlertDialog {

    public DeviceMessageDialog(final IButtonPusher activity) {
        super(activity.getContext());

        LayoutInflater inflater = getLayoutInflater();
        View dialoglayout = inflater.inflate(R.layout.device_message_dialog, null);
        setView(dialoglayout);

        final EditText deviceTopMessageEt = (EditText) dialoglayout.findViewById(R.id.device_message_top_value);

        if (!activity.getTopMessage().equals("undefined")) {
            deviceTopMessageEt.setText(activity.getTopMessage());
        }

        final EditText deviceBottomMessageEt = (EditText) dialoglayout.findViewById(R.id.device_message_bottom_value);

        if (!activity.getBotttomMessage().equals("undefined")) {
            deviceBottomMessageEt.setText(activity.getBotttomMessage());
        }

        setTitle(R.string.rfduino_device_message);
        setButton(DialogInterface.BUTTON_POSITIVE, activity.getContext().getResources().getString(R.string.dialog_ok), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                activity.setMessage(deviceTopMessageEt.getText().toString(), deviceBottomMessageEt.getText().toString());
            }
        });

        setButton(DialogInterface.BUTTON_NEGATIVE, activity.getContext().getResources().getString(R.string.cancel), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
    }
}
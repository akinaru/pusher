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
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.widget.ListView;

import com.github.akinaru.roboticbuttonpusher.R;
import com.github.akinaru.roboticbuttonpusher.adapter.OpenSourceItemAdapter;

/**
 * @author Bertrand Martel
 */
public class OpenSourceItemsDialog extends AlertDialog {

    public OpenSourceItemsDialog(Context context) {
        super(context);

        LayoutInflater inflater = LayoutInflater.from(context);
        ListView listview = (ListView) inflater.inflate(R.layout.open_source_list, null);
        listview.setAdapter(new OpenSourceItemAdapter(context));

        setView(listview);
        setTitle(R.string.open_source_items);
        setButton(DialogInterface.BUTTON_POSITIVE, "Ok",
                (OnClickListener) null);
    }
}

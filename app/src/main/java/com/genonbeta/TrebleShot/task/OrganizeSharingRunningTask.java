/*
 * Copyright (C) 2019 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.genonbeta.TrebleShot.task;

import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.activity.AddDevicesToTransferActivity;
import com.genonbeta.TrebleShot.activity.ViewTransferActivity;
import com.genonbeta.TrebleShot.app.Activity;
import com.genonbeta.TrebleShot.database.AccessDatabase;
import com.genonbeta.TrebleShot.object.TransferDescriptor;
import com.genonbeta.TrebleShot.object.TransferGroup;
import com.genonbeta.TrebleShot.object.TransferObject;
import com.genonbeta.TrebleShot.service.WorkerService;
import com.genonbeta.TrebleShot.util.AppUtils;
import com.genonbeta.TrebleShot.util.FileUtils;
import com.genonbeta.TrebleShot.util.TransferUtils;
import com.genonbeta.android.database.SQLQuery;
import com.genonbeta.android.framework.io.DocumentFile;
import com.genonbeta.android.framework.util.Interrupter;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public class OrganizeSharingRunningTask extends WorkerService.AttachableRunningTask<WorkerService.AttachedTaskListener>
{
    private List<Uri> mUriList;

    public OrganizeSharingRunningTask(List<Uri> fileUris)
    {
        mUriList = fileUris;
    }

    @Override
    public void onRun() throws InterruptedException
    {
        if (getAnchorListener() != null) {
            getAnchorListener().setTaskPosition(0, mUriList.size());
            publishStatusText(getService().getString(R.string.mesg_organizingFiles));
        }

        final AccessDatabase database = AppUtils.getDatabase(getService());
        final SQLiteDatabase instance = database.getWritableDatabase();
        final TransferGroup group = new TransferGroup(AppUtils.getUniqueNumber());
        final List<TransferObject> list = new ArrayList<>();

        for (int position = 0; position < mUriList.size(); position++) {
            if (getInterrupter().interrupted())
                throw new InterruptedException();

            publishStatusText(getService().getString(R.string.text_transferStatusFiles, position, mUriList.size()));

            if (getAnchorListener() != null)
                getAnchorListener().updateTaskPosition(1, 0);

            Uri fileUri = mUriList.get(position);

            try {
                DocumentFile file = FileUtils.fromUri(getService(), fileUri);

                if (file.isDirectory())
                    TransferUtils.createFolderStructure(list, group.id, file, file.getName(),
                            getInterrupter(), getAnchorListener());
                else
                    list.add(TransferObject.from(file, group.id, null));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        if (getAnchorListener() != null)
            publishStatusText(getService().getString(R.string.mesg_completing));

        database.insert(instance, list, (total, current) -> {
            if (getAnchorListener() != null)
                getAnchorListener().setTaskPosition(total, current);

            return !getInterrupter().interrupted();
        }, group);

        getInterrupter().addCloser((userAction) -> database.remove(instance, new SQLQuery.Select(
                AccessDatabase.TABLE_TRANSFER).setWhere(String.format("%s = ?", AccessDatabase.FIELD_TRANSFER_GROUPID),
                String.valueOf(group.id))));

        database.insert(instance, group, null);
        ViewTransferActivity.startInstance(getService(), group.id);
        AddDevicesToTransferActivity.startInstance(getService(), group.id, true);
        database.broadcast();

        if (getAnchorListener() instanceof Activity)
            ((Activity) getAnchorListener()).finish();
    }
}

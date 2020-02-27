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

package com.genonbeta.TrebleShot.service;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.app.Service;
import com.genonbeta.TrebleShot.util.AppUtils;
import com.genonbeta.TrebleShot.util.DynamicNotification;
import com.genonbeta.TrebleShot.util.InterruptAwareJob;
import com.genonbeta.TrebleShot.util.NotificationUtils;
import com.genonbeta.android.framework.util.Interrupter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * created by: Veli
 * date: 26.01.2018 14:21
 */

public class WorkerService extends Service
{
    public static final String TAG = WorkerService.class.getSimpleName();

    public static final String ACTION_KILL_SIGNAL = "com.genonbeta.intent.action.KILL_SIGNAL";
    public static final String ACTION_KILL_ALL_SIGNAL = "com.genonbeta.intent.action.KILL_ALL_SIGNAL";
    public static final String EXTRA_TASK_HASH = "extraTaskId";

    public static final int REQUEST_CODE_RESCUE_TASK = 10910;
    public static final int ID_NOTIFICATION_FOREGROUND = 1103;

    private final List<RunningTask> mTaskList = new ArrayList<>();
    private ExecutorService mExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private LocalBinder mBinder = new LocalBinder();
    private NotificationUtils mNotificationUtils;
    private DynamicNotification mNotification;

    public static int intentHash(@NonNull Intent intent)
    {
        StringBuilder builder = new StringBuilder();

        builder.append(intent.getComponent());
        builder.append(intent.getData());
        builder.append(intent.getPackage());
        builder.append(intent.getAction());
        builder.append(intent.getFlags());
        builder.append(intent.getType());

        if (intent.getExtras() != null)
            builder.append(intent.getExtras().toString());

        return builder.toString().hashCode();
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        mNotificationUtils = new NotificationUtils(this, getDatabase(), getDefaultPreferences());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (intent != null)
            if (ACTION_KILL_SIGNAL.equals(intent.getAction()) && intent.hasExtra(EXTRA_TASK_HASH)) {
                int taskHash = intent.getIntExtra(EXTRA_TASK_HASH, -1);

                RunningTask runningTask = findTaskByHash(taskHash);

                if (runningTask == null || runningTask.getInterrupter().interrupted())
                    getNotificationUtils().cancel(taskHash);
                else {
                    runningTask.getInterrupter().interrupt();
                    runningTask.onInterrupted();
                }
            } else if (ACTION_KILL_ALL_SIGNAL.equals(intent.getAction())) {
                synchronized (getTaskList()) {
                    for (RunningTask runningTask : getTaskList()) {
                        runningTask.getInterrupter().interrupt();
                        runningTask.onInterrupted();
                    }
                }
            }

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        synchronized (getTaskList()) {
            for (RunningTask runningTask : getTaskList())
                runningTask.getInterrupter().interrupt(false);
        }
    }

    public synchronized RunningTask findTaskByHash(int hashCode)
    {
        synchronized (getTaskList()) {
            for (RunningTask runningTask : getTaskList())
                if (runningTask.hashCode() == hashCode)
                    return runningTask;
        }

        return null;
    }

    public List<RunningTask> getTaskList()
    {
        return mTaskList;
    }

    public void publishNotification(RunningTask runningTask)
    {
        if (runningTask.mNotification == null) {
            PendingIntent cancelIntent = PendingIntent.getService(this, AppUtils.getUniqueNumber(),
                    new Intent(this, WorkerService.class)
                            .setAction(ACTION_KILL_SIGNAL)
                            .putExtra(EXTRA_TASK_HASH, runningTask.hashCode()), 0);

            runningTask.mNotification = mNotificationUtils.buildDynamicNotification(runningTask.hashCode(),
                    NotificationUtils.NOTIFICATION_CHANNEL_LOW);

            runningTask.mNotification.setSmallIcon(runningTask.getIconRes() == 0
                    ? R.drawable.ic_autorenew_white_24dp_static : runningTask.getIconRes())
                    .setContentTitle(getString(R.string.text_taskOngoing))
                    .addAction(R.drawable.ic_close_white_24dp_static,
                            getString(R.string.butn_cancel), cancelIntent);

            if (runningTask.mActivityIntent != null)
                runningTask.mNotification.setContentIntent(runningTask.mActivityIntent);
        }

        runningTask.mNotification.setContentTitle(runningTask.getTitle())
                .setContentText(runningTask.getStatusText());

        runningTask.mNotification.show();
    }

    public void publishForegroundNotification()
    {
        if (mNotification == null) {
            mNotification = mNotificationUtils.buildDynamicNotification(ID_NOTIFICATION_FOREGROUND,
                    NotificationUtils.NOTIFICATION_CHANNEL_LOW);
            mNotification.setSmallIcon(R.drawable.ic_autorenew_white_24dp_static)
                    .setContentTitle(getString(R.string.text_taskOngoing));
        }

        mNotification.setContentText(getString(R.string.text_workerService));
        startForeground(ID_NOTIFICATION_FOREGROUND, mNotification.build());
    }

    protected synchronized void registerWork(RunningTask runningTask)
    {
        synchronized (getTaskList()) {
            getTaskList().add(runningTask);
        }

        publishForegroundNotification();
        publishNotification(runningTask);
    }

    public void run(final RunningTask runningTask)
    {
        mExecutor.submit(() -> {
            runningTask.setService(WorkerService.this);
            registerWork(runningTask);

            try {
                runningTask.run();
            } catch (Exception e) {
                e.printStackTrace();
            }

            unregisterWork(runningTask);
            runningTask.setService(null);
        });
    }

    protected synchronized void unregisterWork(RunningTask runningTask)
    {
        runningTask.mNotification.cancel();

        synchronized (getTaskList()) {
            getTaskList().remove(runningTask);

            if (getTaskList().size() <= 0)
                stopForeground(true);
        }
    }

    public interface AttachedTaskListener
    {
        void onAttachedToTask(BaseAttachableRunningTask task);

        void setTaskPosition(int ofTotal, int total);

        void updateTaskPosition(int addToOfTotal, int addToTotal);

        void updateTaskStatus(String text);
    }

    public abstract static class RunningTask extends InterruptAwareJob
    {
        private Interrupter mInterrupter;
        private WorkerService mService;
        private String mStatusText;
        private String mTitle;
        private int mIconRes;
        private long mLastNotified = 0;
        private int mHash = 0;
        private DynamicNotification mNotification;
        private PendingIntent mActivityIntent;

        abstract protected void onRun() throws InterruptedException;

        protected void onInterrupted()
        {

        }

        @Override
        public int hashCode()
        {
            return mHash != 0 ? mHash : super.hashCode();
        }

        @Nullable
        public PendingIntent getContentIntent()
        {
            return mActivityIntent;
        }

        public int getIconRes()
        {
            return mIconRes;
        }

        public Interrupter getInterrupter()
        {
            if (mInterrupter == null)
                mInterrupter = new Interrupter();

            return mInterrupter;
        }

        protected WorkerService getService()
        {
            return mService;
        }

        public String getStatusText()
        {
            return mStatusText;
        }

        public String getTitle()
        {
            return mTitle;
        }

        public boolean publishStatusText(String text)
        {
            mStatusText = text;

            if (System.currentTimeMillis() - mLastNotified > 2000) {
                mService.publishNotification(this);
                mLastNotified = System.currentTimeMillis();

                return true;
            }
            return false;
        }

        protected void run()
        {
            try {
                run(getInterrupter());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public boolean run(final Context context)
        {
            ServiceConnection serviceConnection = new ServiceConnection()
            {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service)
                {
                    AppUtils.startForegroundService(context, new Intent(context, WorkerService.class));

                    WorkerService workerService = ((WorkerService.LocalBinder) service).getService();
                    workerService.run(RunningTask.this);

                    context.unbindService(this);
                }

                @Override
                public void onServiceDisconnected(ComponentName name)
                {

                }
            };

            return context.bindService(new Intent(context, WorkerService.class), serviceConnection,
                    Context.BIND_AUTO_CREATE);
        }

        public RunningTask setContentIntent(PendingIntent intent)
        {
            mActivityIntent = intent;
            return this;
        }

        public RunningTask setContentIntent(Context context, Intent intent)
        {
            mHash = intentHash(intent);
            return setContentIntent(PendingIntent.getActivity(context, 0, intent, 0));
        }

        public RunningTask setIconRes(int iconRes)
        {
            mIconRes = iconRes;
            return this;
        }

        public RunningTask setInterrupter(Interrupter interrupter)
        {
            mInterrupter = interrupter;
            return this;
        }

        private void setService(@Nullable WorkerService service)
        {
            mService = service;
        }

        public RunningTask setTitle(String title)
        {
            mTitle = title;
            return this;
        }
    }

    abstract public static class BaseAttachableRunningTask extends RunningTask
    {
        abstract public void detachAnchor();
    }

    abstract public static class AttachableRunningTask<T extends AttachedTaskListener> extends BaseAttachableRunningTask
    {
        private T mAnchorListener;

        @Override
        public void detachAnchor()
        {
            mAnchorListener = null;
        }

        @Nullable
        public T getAnchorListener()
        {
            return mAnchorListener;
        }

        public AttachableRunningTask<T> setAnchorListener(T listener)
        {
            mAnchorListener = listener;
            listener.onAttachedToTask(this);
            return this;
        }

        @Override
        public boolean publishStatusText(String text)
        {
            if (mAnchorListener != null)
                mAnchorListener.updateTaskStatus(text);

            return super.publishStatusText(text);
        }
    }

    public class LocalBinder extends Binder
    {
        public WorkerService getService()
        {
            return WorkerService.this;
        }
    }
}

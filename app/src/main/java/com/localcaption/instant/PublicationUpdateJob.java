package com.localcaption.instant;

import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;

/** Optional, OS-scheduled update. No network is needed to translate. */
public final class PublicationUpdateJob extends JobService {
    private static final int JOB=72104;
    private volatile JobParameters active;
    static boolean enabled(Context c){return c.getSharedPreferences("publication-library",0).getBoolean("weekly",false);}
    static void setEnabled(Context c,boolean on){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!on){scheduler.cancel(JOB);c.getSharedPreferences("publication-library",0).edit().putBoolean("weekly",false).apply();return;}
        if(c.checkSelfPermission(android.Manifest.permission.INTERNET)!=PackageManager.PERMISSION_GRANTED)return;
        JobInfo info=new JobInfo.Builder(JOB,new ComponentName(c,PublicationUpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setRequiresCharging(true)
            .setRequiresDeviceIdle(true).setPersisted(true).setPeriodic(7L*24*60*60*1000,24L*60*60*1000).build();
        boolean scheduled=scheduler.schedule(info)==JobScheduler.RESULT_SUCCESS;
        c.getSharedPreferences("publication-library",0).edit().putBoolean("weekly",scheduled).apply();
    }
    @Override public boolean onStartJob(JobParameters params){
        if(!enabled(this)||CaptionState.S.running||CaptionState.S.preparing||ModelStore.active)return false;
        android.content.SharedPreferences prefs=getSharedPreferences("engine",0);
        String from=prefs.getString("speechSource",""),to=prefs.getString("speechTarget","");
        if(!PublicationLibrary.supports(from,to)||!PublicationLibrary.start())return false;
        active=params;
        new Thread(()->{
            try{PublicationLibrary.synchronize(getApplicationContext(),from,to,message->{});}
            catch(Exception e){android.util.Log.w("PublicationLibrary","Scheduled update failed",e);}
            finally{
                PublicationLibrary.finish();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                    if(active==params){active=null;jobFinished(params,false);}
                });
            }
        },"weekly-publication-update").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        if(active==params){active=null;PublicationLibrary.cancel();}
        return true;
    }
}

package app.qiandao.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

public class RelayService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("relay", "订阅转接", NotificationManager.IMPORTANCE_LOW));
        Notification n = new NotificationCompat.Builder(this, "relay")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("订阅转接运行中")
            .setContentText("本机地址 127.0.0.1:" + Relay.PORT)
            .setOngoing(true)
            .build();
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        try {
            new Relay(this).start();
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (Relay.running != null) Relay.running.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

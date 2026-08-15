package app.trailsafe;

import android.app.Application;

public final class TrailSafeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CliRunner.prepare(this);
    }
}

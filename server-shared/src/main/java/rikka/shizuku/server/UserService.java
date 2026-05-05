package rikka.shizuku.server;

import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextHidden;
import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.rikka.tools.refine.Refine;

public class UserService {

    private static String TAG;

    public static void setTag(String tag) {
        UserService.TAG = tag;
    }

    @Nullable
    public static Pair<IBinder, String> create(String[] args) {
        String name = null;
        String token = null;
        String pkg = null;
        String cls = null;
        int uid = -1;

        for (String arg : args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8);
            } else if (arg.startsWith("--package=")) {
                pkg = arg.substring(10);
            } else if (arg.startsWith("--class=")) {
                cls = arg.substring(8);
            } else if (arg.startsWith("--uid=")) {
                uid = Integer.parseInt(arg.substring(6));
            }
        }

        int userId = uid / 100000;

        Log.i(TAG, String.format("starting service %s/%s...", pkg, cls));

        IBinder service;

        try {
            ActivityThread activityThread = ActivityThread.systemMain();
            Context systemContext = activityThread.getSystemContext();

            DdmHandleAppName.setAppName(name != null ? name : pkg + ":user_service", userId);

            //noinspection InstantiationOfUtilityClass
            UserHandle userHandle = Refine.unsafeCast(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? UserHandleHidden.of(userId)
                            : new UserHandleHidden(userId));
            Context context = Refine.<ContextHidden>unsafeCast(systemContext).createPackageContextAsUser(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
            
            Application application = null;
            ClassLoader classLoader = null;
            
            // Try to create Application instance with graceful fallback
            try {
                Field mPackageInfo = context.getClass().getDeclaredField("mPackageInfo");
                mPackageInfo.setAccessible(true);
                Object loadedApk = mPackageInfo.get(context);
                Method makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
                
                try {
                    // First attempt: try with a minimal Instrumentation instance
                    Instrumentation instrumentation = new Instrumentation();
                    application = (Application) makeApplication.invoke(loadedApk, true, instrumentation);
                } catch (Exception e1) {
                    // Fallback: try with null Instrumentation
                    try {
                        application = (Application) makeApplication.invoke(loadedApk, true, null);
                    } catch (Exception e2) {
                        // If both fail, log and fall back to using Context
                        Log.w(TAG, "Failed to create Application instance, falling back to Context", e2);
                        application = null;
                    }
                }
                
                if (application != null) {
                    // Successfully created Application, set it as the initial application
                    Field mInitialApplication = activityThread.getClass().getDeclaredField("mInitialApplication");
                    mInitialApplication.setAccessible(true);
                    mInitialApplication.set(activityThread, application);
                    classLoader = application.getClassLoader();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error during Application initialization", e);
                application = null;
            }
            
            // If Application creation failed, fall back to Context
            if (application == null) {
                classLoader = context.getClassLoader();
            }
            
            Class<?> serviceClass = classLoader.loadClass(cls);
            Constructor<?> constructorWithContext = null;
            try {
                constructorWithContext = serviceClass.getConstructor(Context.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
            }
            if (constructorWithContext != null) {
                // Pass Application if available, otherwise pass Context
                service = (IBinder) constructorWithContext.newInstance(application != null ? application : context);
            } else {
                service = (IBinder) serviceClass.newInstance();
            }
        } catch (Throwable tr) {
            Log.w(TAG, String.format("unable to start service %s/%s...", pkg, cls), tr);
            return null;
        }

        return new Pair<>(service, token);
    }
}

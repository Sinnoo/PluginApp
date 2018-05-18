/*
 * Copyright (C) 2015 HouKx <hkx.aidream@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lange.commonplugin;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器
 *
 * @author HouKangxi
 * @author Lody
 */
public class PluginManager implements FileFilter {

    private static final String TAG = PluginManager.class.getSimpleName();

    private static String mPhoneInfo = "";//手机的体系机制类型 armeabi

    /**
     * 插件管理器单例
     */
    private static PluginManager SINGLETON;

    /**
     * 插件包名 -- 插件信息 的映射
     */
    private final Map<String, PlugInfo> pluginPkgToInfoMap = new ConcurrentHashMap<String, PlugInfo>();

    /**
     * 全局上下文
     */
    private Context context;
    /**
     * 插件dex opt输出路径
     */
    private String dexOutputPath;
    /**
     * 私有目录中存储插件的路径
     */
    private File dexInternalStoragePath;

    /**
     * Activity生命周期监听器
     */
    //private PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback;

    /**
     * 插件过期验证器 (默认只对比文件长度)
     */
    private PluginOverdueVerifier pluginOverdueVerifier = new SimpleLengthVerifier();

    //private PluginInstrumentation pluginInstrumentation;

    //private Handler uiHandler;


    /**
     * 插件管理器私有构造器
     *
     * @param context Application上下文
     */
    private PluginManager(Context context) {
        /*
        if (!isMainThread()) {
            throw new IllegalThreadStateException("PluginManager must init in UI Thread!");
        }
        */
        this.context = context;
        File optimizedDexPath = context.getDir(Globals.PRIVATE_PLUGIN_OUTPUT_DIR_NAME, Context.MODE_PRIVATE);
        dexOutputPath = optimizedDexPath.getAbsolutePath();
        dexInternalStoragePath = context.getDir(Globals.PRIVATE_PLUGIN_ODEX_OUTPUT_DIR_NAME, Context.MODE_PRIVATE);
        /*
        uiHandler = new Handler(Looper.getMainLooper());
        DelegateActivityThread delegateActivityThread = DelegateActivityThread.getSingleton();
        Instrumentation originInstrumentation = delegateActivityThread.getInstrumentation();
        if (originInstrumentation instanceof PluginInstrumentation) {
            pluginInstrumentation = (PluginInstrumentation) originInstrumentation;
        } else {
            pluginInstrumentation = new PluginInstrumentation(originInstrumentation);
            delegateActivityThread.setInstrumentation(pluginInstrumentation);
        }
        */
    }


    /**
     * 取得插件管理器单例,<br>
     * NOTICE: 你必须在启动插件管理器前初始化插件管理器!
     *
     * @return 插件管理器单例
     */
    public static PluginManager getSingleton() {
        checkInit();
        return SINGLETON;
    }


    private static void checkInit() {
        if (SINGLETON == null) {
            throw new IllegalStateException("Please init the PluginManager first!");
        }
    }

    /**
     * 初始化插件管理器,请不要传入易变的Context,那将造成内存泄露!
     *
     * @param context Application上下文
     */
    public static void init(Context context) {
        if (SINGLETON != null) {
            Log.w(TAG, "PluginManager have been initialized, YOU needn't initialize it again!");
            return;
        }
        Log.i(TAG, "init PluginManager...");
        SINGLETON = new PluginManager(context);
    }

    /*public void reinitFilePath() {
        File optimizedDexPath = context.getDir(Globals.PRIVATE_PLUGIN_OUTPUT_DIR_NAME, Context.MODE_PRIVATE);
        dexOutputPath = optimizedDexPath.getAbsolutePath();
        dexInternalStoragePath = context.getDir(Globals.PRIVATE_PLUGIN_ODEX_OUTPUT_DIR_NAME, Context.MODE_PRIVATE);
    }*/
    /**
     * 尝试通过插件ID或插件包名取得插件信息,如果插件没有找到,抛出异常.
     *
     * @param plugPkg 插件ID或插件包名
     * @return 插件信息
     */
    public PlugInfo tryGetPluginInfo(String plugPkg) throws PluginNotFoundException {
        PlugInfo plug = findPluginByPackageName(plugPkg);
        if (plug == null) {
            throw new PluginNotFoundException("plug not found by:" + plugPkg);
        }
        return plug;
    }

    public File getPluginBasePath(PlugInfo plugInfo) {
        return new File(getDexInternalStoragePath(), plugInfo.getId() + "-dir");
    }

    public File getPluginLibPath(PlugInfo plugInfo) {
        return new File(getDexInternalStoragePath(), plugInfo.getId() + "-dir/lib/");
    }


    /**
     * 指定插件包名取得插件信息
     *
     * @param packageName 插件包名
     * @return 插件信息
     */
    public PlugInfo findPluginByPackageName(String packageName) {
        return pluginPkgToInfoMap.get(packageName);
    }

    /**
     * 取得当前维护的全部插件
     *
     * @return 当前维护的全部插件
     */
    public Collection<PlugInfo> getPlugins() {
        return pluginPkgToInfoMap.values();
    }

    /**
     * 指定包名卸载一个插件
     *
     * @param pkg 插件包名
     */
    public void uninstallPluginByPkg(String pkg) {
        removePlugByPkg(pkg);
    }


    private PlugInfo removePlugByPkg(String pkg) {
        PlugInfo pl;
        synchronized (this) {
            pl = pluginPkgToInfoMap.remove(pkg);
            if (pl == null) {
                return null;
            }
        }
        return pl;
    }

    /**
     * 加载指定插件或指定目录下的所有插件
     * <p>
     * 都使用文件名作为Id
     *
     * @param pluginSrcDirFile - apk或apk目录
     * @return 插件集合
     * @throws Exception
     */
    //public Collection<PlugInfo> loadPlugin(final File pluginSrcDirFile)
    public PlugInfo loadPlugin(final File pluginSrcDirFile) throws Exception {
        if (pluginSrcDirFile == null || !pluginSrcDirFile.exists()) {
            Log.e(TAG, "invalidate plugin file or Directory :" + pluginSrcDirFile);
            return null;
        }
        PlugInfo one = null;
        if (pluginSrcDirFile.isFile()) {
            one = buildPlugInfo(pluginSrcDirFile, null, null);
            if (one != null) {
                savePluginToMap(one);
            }
            //return Collections.singletonList(one);

        }
        return one;
        //暂时不支持目录下多个apk
        /*
        File[] pluginApkFiles = pluginSrcDirFile.listFiles(this);
        if (pluginApkFiles == null || pluginApkFiles.length == 0) {
            throw new FileNotFoundException("could not find plugins in:"
                    + pluginSrcDirFile);
        }
        for (File pluginApk : pluginApkFiles) {
            try {
                PlugInfo plugInfo = buildPlugInfo(pluginApk, null, null);
                if (plugInfo != null) {
                    savePluginToMap(plugInfo);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return pluginPkgToInfoMap.values();
        */
    }

    public PlugInfo loadPluginDex(final File pluginSrcDirFile,final String dexFile)
        throws Exception {
            if (pluginSrcDirFile == null || !pluginSrcDirFile.exists()) {
                Log.e(TAG, "invalidate plugin file or Directory :"
                        + pluginSrcDirFile);
                return null;
            }
            PlugInfo one = null;
            if (pluginSrcDirFile.isFile()) {
                one = buildPlugInfo(pluginSrcDirFile, null, dexFile);
                if (one != null) {
                    savePluginToMap(one);
                }
                //return Collections.singletonList(one);

            }
            return one;
    }

    //public Collection<PlugInfo> loadPlugin(final File pluginSrcDirFile)
    public PlugInfo loadMyPlugin(final File pluginSrcDirFile)
            throws Exception {
        if (pluginSrcDirFile == null || !pluginSrcDirFile.exists()) {
            Log.e(TAG, "invalidate plugin file or Directory :"
                    + pluginSrcDirFile);
            return null;
        }
        PlugInfo one = null;
        if (pluginSrcDirFile.isFile()) {
            one = buildMyPlugInfo(pluginSrcDirFile);
        }
        return one;
    }

    private synchronized void savePluginToMap(PlugInfo plugInfo) {
        pluginPkgToInfoMap.put(plugInfo.getPackageName(), plugInfo);
    }


    private PlugInfo buildPlugInfo(File pluginApk, String pluginId, String targetFileName) throws Exception {
        PlugInfo info = new PlugInfo();
        //如果没有设置pluginId，用apk的文件名做id
        info.setId(pluginId == null ? pluginApk.getName() : pluginId);

        //privateFile : /data/data/com.lange.plugin/app_plugins-opt/xxxx.apk
        //需要把sd卡上的apk保存到应用的私有路径
        //privateFile 是apk保存的私有路径
        File privateFile = new File(dexInternalStoragePath, targetFileName == null ? pluginApk.getName() : targetFileName);

        info.setFilePath(privateFile.getAbsolutePath());
        //Copy Plugin to Private Dir
        //如果是载入sd卡上的apk，先拷贝到应用私有路径下
        if (!privateFile.exists()) {
            copyApkToPrivatePath(pluginApk, privateFile);
        }

        //dexClassLoader可以载入 jar 、apk 文件
        String dexPath = privateFile.getAbsolutePath();
        //Load Plugin Manifest
        //获取一些apk里面 MainFest.xml的一些内容，包括 pacageName 、Activity 、application 等
        PluginManifestUtil.setManifestInfo(context, dexPath, info,mPhoneInfo);

        //Load Plugin Res
        //通过反射的方式获取 AssetManager 的 addAssetPath 方法，把apk中的资源加入到 Resources 中
        try {
            AssetManager am = AssetManager.class.newInstance();
            am.getClass().getMethod("addAssetPath", String.class).invoke(am, dexPath);
            info.setAssetManager(am);
            Resources hotRes = context.getResources();
            Resources res = new Resources(am, hotRes.getDisplayMetrics(), hotRes.getConfiguration());
            info.setResources(res);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //Load  classLoader for Plugin
        PluginClassLoader pluginClassLoader = new PluginClassLoader(info, dexPath, dexOutputPath
                , getPluginLibPath(info).getAbsolutePath(), this.context.getClassLoader());
                //, getPluginLibPath(info).getAbsolutePath(), ClassLoader.getSystemClassLoader().getParent());
        info.setClassLoader(pluginClassLoader);
        ApplicationInfo appInfo = info.getPackageInfo().applicationInfo;
        String appClassName = null;
        if (appInfo != null) {
            appClassName = appInfo.name;
        }
        Application app = makeApplication(pluginClassLoader, appClassName);
        attachBaseContext(info, app);
        info.setApplication(app);
        Log.i(TAG, "buildPlugInfo: " + info);
        return info;
    }
    private PlugInfo buildMyPlugInfo(File pluginApk) throws Exception {
        PlugInfo info = new PlugInfo();
        //如果没有设置pluginId，用apk的文件名做id

        info.setId(pluginApk.getName());

        //Load  classLoader for Plugin
        PluginClassLoader pluginClassLoader = new PluginClassLoader(info, pluginApk.getAbsolutePath(), dexOutputPath
                , getPluginLibPath(info).getAbsolutePath(), this.context.getClassLoader());
        //, getPluginLibPath(info).getAbsolutePath(), ClassLoader.getSystemClassLoader().getParent());
        info.setClassLoader(pluginClassLoader);
        ApplicationInfo appInfo = info.getPackageInfo().applicationInfo;
        String appClassName = null;
        if (appInfo != null) {
            appClassName = appInfo.name;
        }
        Application app = makeApplication(pluginClassLoader, appClassName);
        attachBaseContext(info, app);
        info.setApplication(app);
        Log.i(TAG, "buildPlugInfo: " + info);
        return info;
    }
    private void attachBaseContext(PlugInfo info, Application app) {
        try {
            Field mBase = ContextWrapper.class.getDeclaredField("mBase");
            mBase.setAccessible(true);
            mBase.set(app, new PluginContext(context.getApplicationContext(), info));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    /**
     * 构造插件的Application
     *
     * @param pluginClassLoader 类加载器
     * @param appClassName      类名
     * @return
     */
    private Application makeApplication(PluginClassLoader pluginClassLoader, String appClassName) {
        if (appClassName != null) {
            try {
                return (Application) pluginClassLoader.loadClass(appClassName).newInstance();
            } catch (Throwable ignored) {
            }
        }

        return new Application();
    }

    public Object getClassInstance(PlugInfo plugInfo,String objClassName){
        if (objClassName != null){
            try {
                return  plugInfo.getClassLoader().loadClass(objClassName).newInstance();
            }catch (Throwable ignored){}
        }
        return null;
    }


    /**
     * 将Apk复制到私有目录
     *
     * @param pluginApk    插件apk原始路径
     * @param targetPutApk 要拷贝到的目标位置
     */
    private void copyApkToPrivatePath(File pluginApk, File targetPutApk) {
        if (targetPutApk.exists() && pluginOverdueVerifier != null && pluginOverdueVerifier.isOverdue(pluginApk, targetPutApk)) {
            return;
        }
        try {
            FileUtils.copyFile(pluginApk, targetPutApk);
        } catch (Exception e){}
    }

    /**
     * @return 存储插件的私有目录
     */
    File getDexInternalStoragePath() {
        return dexInternalStoragePath;
    }

    Context getContext() {
        return context;
    }

    /**
     * @return 插件Activity生命周期监听器
     */
    //public PluginActivityLifeCycleCallback getPluginActivityLifeCycleCallback() {
    //    return pluginActivityLifeCycleCallback;
    //}

    /**
     * 设置插件Activity生命周期监听器
     *
     * @param pluginActivityLifeCycleCallback 插件Activity生命周期监听器
     */
    //public void setPluginActivityLifeCycleCallback(
    //        PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback) {
    //    this.pluginActivityLifeCycleCallback = pluginActivityLifeCycleCallback;
    //}

    /**
     * @return 插件验证校检器
     */
    public PluginOverdueVerifier getPluginOverdueVerifier() {
        return pluginOverdueVerifier;
    }

    /**
     * 设置插件验证校检器
     *
     * @param pluginOverdueVerifier 插件验证校检器
     */
    public void setPluginOverdueVerifier(PluginOverdueVerifier pluginOverdueVerifier) {
        this.pluginOverdueVerifier = pluginOverdueVerifier;
    }

    @Override
    public boolean accept(File pathname) {
        return !pathname.isDirectory() && pathname.getName().endsWith(".apk");
    }


    //======================================================
    //=================启动插件相关方法=======================
    //======================================================


    /* public void startMainActivity(Context from, PlugInfo plugInfo) {
         if (!pluginPkgToInfoMap.containsKey(plugInfo.getPackageName())) {
             return;
         }
         ActivityInfo activityInfo = plugInfo.getMainActivity().activityInfo;
         if (activityInfo == null) {
             throw new ActivityNotFoundException("Cannot find Main Activity from plugin.");
         }
         String mainActivityName = plugInfo.getMainActivity().activityInfo.name;
         CreateActivityData createActivityData = new CreateActivityData(mainActivityName, plugInfo.getPackageName());
         Intent intent = new Intent(from, Globals.selectDynamicActivity(activityInfo));
         intent.putExtra(Globals.FLAG_ACTIVITY_FROM_PLUGIN, createActivityData);
         from.startActivity(innt);
     }

     public void startMainActivity(Context from, String pluginPkgName) throws PluginNotFoundException, ActivityNotFoundException {
         PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
         startMainActivity(from, plugInfo);
     }

     public void startActivity(Context from, PlugInfo plugInfo, String targetActivity) {
         ActivityInfo activityInfo = plugInfo.findActivityByClassName(targetActivity);
         if (activityInfo == null) {
             throw new ActivityNotFoundException("Cannot find " + targetActivity + " from plugin, could you declare this Activity in plugin?");
         }
         CreateActivityData createActivityData = new CreateActivityData(targetActivity, plugInfo.getPackageName());
         Intent intent = new Intent(from, Globals.selectDynamicActivity(activityInfo));
         intent.putExtra(Globals.FLAG_ACTIVITY_FROM_PLUGIN, createActivityData);
         from.startActivity(intent);
     }

     public void startActivity(Context from, String pluginPkgName, String targetActivity) throws PluginNotFoundException, ActivityNotFoundException {
         PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
         startActivity(from, plugInfo, targetActivity);
     }*/
    /* /**/



    /**
     * 启动插件的主Activity
     *
     * @param from     fromContext
     * @param plugInfo 插件Info
     * @param intent   通过此Intent可以向插件传参, 可以为null
     */
    public void startMainActivity(Context from, PlugInfo plugInfo, Intent intent) {
        if (!pluginPkgToInfoMap.containsKey(plugInfo.getPackageName())) {
            return;
        }
        ActivityInfo activityInfo = plugInfo.getMainActivity().activityInfo;
        if (activityInfo == null) {
            throw new ActivityNotFoundException("Cannot find Main Activity from plugin.");
        }
        startActivity(from, plugInfo, activityInfo, intent);

    }

    /**
     * 启动插件的主Activity
     *
     * @param from     fromContext
     * @param plugInfo 插件Info
     */
    public void startMainActivity(Context from, PlugInfo plugInfo) {
        startMainActivity(from, plugInfo, null);
    }

    /**
     * 启动插件的主Activity
     * @param from fromContext
     * @param pluginPkgName 插件包名
     * @throws PluginNotFoundException 该插件未加载时抛出
     * @throws ActivityNotFoundException 插件Activity信息找不到时抛出
     */
    public void startMainActivity(Context from, String pluginPkgName) throws PluginNotFoundException, ActivityNotFoundException {
        PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
        startMainActivity(from, plugInfo);
    }


    /**
     * 启动插件的指定Activity
     *
     * @param from         fromContext
     * @param plugInfo     插件信息
     * @param activityInfo 要启动的插件activity信息
     * @param intent       通过此Intent可以向插件传参, 可以为null
     */
    public void startActivity(Context from, PlugInfo plugInfo, ActivityInfo activityInfo, Intent intent) {
        if (activityInfo == null) {
            throw new ActivityNotFoundException("Cannot find ActivityInfo from plugin, could you declare this Activity in plugin?");
        }
        if (intent == null) {
            intent = new Intent();
        }
        CreateActivityData createActivityData = new CreateActivityData(activityInfo.name, plugInfo.getPackageName());
        intent.setClass(from, DefaultActivitySelector.getDefault().selectDynamicActivity(activityInfo));
        intent.putExtra(Globals.FLAG_ACTIVITY_FROM_PLUGIN, createActivityData);
        from.startActivity(intent);
    }


    /*public DynamicActivitySelector getActivitySelector() {
        return activitySelector;
    }

    public void setActivitySelector(DynamicActivitySelector activitySelector) {
        if (activitySelector == null) {
            activitySelector = DefaultActivitySelector.getDefault();
        }
        this.activitySelector = activitySelector;
    }*/

    /**
     * 启动插件的指定Activity
     *
     * @param from           fromContext
     * @param plugInfo       插件信息
     * @param targetActivity 要启动的插件activity类名
     * @param intent         通过此Intent可以向插件传参, 可以为null
     */
    public void startActivity(Context from, PlugInfo plugInfo, String targetActivity, Intent intent) {
        ActivityInfo activityInfo = plugInfo.findActivityByClassName(targetActivity);
        startActivity(from, plugInfo, activityInfo, intent);
    }

    /**
     * 启动插件的指定Activity
     *
     * @param from           fromContext
     * @param plugInfo       插件信息
     * @param targetActivity 要启动的插件activity类名
     */
    public void startActivity(Context from, PlugInfo plugInfo, String targetActivity) {
        startActivity(from, plugInfo, targetActivity, null);
    }


    /**
     * 启动插件的指定Activity
     *
     * @param from           fromContext
     * @param pluginPkgName  插件包名
     * @param targetActivity 要启动的插件activity类名
     */
    public void startActivity(Context from, String pluginPkgName, String targetActivity) throws PluginNotFoundException, ActivityNotFoundException {
        startActivity(from, pluginPkgName, targetActivity, null);
    }

    /**
     * 启动插件的指定Activity
     *
     * @param from           fromContext
     * @param pluginPkgName  插件包名
     * @param targetActivity 要启动的插件activity类名
     * @param intent         通过此Intent可以向插件传参, 可以为null
     */
    public void startActivity(Context from, String pluginPkgName, String targetActivity, Intent intent) throws PluginNotFoundException, ActivityNotFoundException {
        PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
        startActivity(from, plugInfo, targetActivity, intent);
    }
    public void dump() {
        Log.d(TAG, pluginPkgToInfoMap.size() + " Plugins is loaded, " + Arrays.toString(pluginPkgToInfoMap.values().toArray()));
    }

    /**
     * @return 当前是否为主线程
     */
    /*
    public boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }
    */

    /**
     * 获取cpu_abi
     * @return
     */
    public static String getPhoneCPU(){
        String phoneInfo = android.os.Build.CPU_ABI;
        if(phoneInfo==null){
            phoneInfo = "armeabi";
        }
        mPhoneInfo = phoneInfo;
        return phoneInfo;
    }


   /* public boolean startMainActivity(Context context, String pkgOrId) {
        Log.d("tag", "startMainActivity by:" + pkgOrId);
        PlugInfo plug = preparePlugForStartActivity(context, pkgOrId);
        if (plug.getMainActivity() == null) {
            Log.e("tag", "startMainActivity: plug.getMainActivity() == null!");
            return false;
        }
        if (plug.getMainActivity().activityInfo == null) {
            Log.e("tag",
                    "startMainActivity: plug.getMainActivity().activityInfo == null!");
            return false;
        }
        context.startActivity(new Intent().setComponent(new ComponentName(
                context, ActivityOverider.targetClassName)).putExtra(ActivityOverider.PLUGIN_ID, pkgOrId)
                .putExtra(ActivityOverider.PLUGIN_ACTIVITY, plug.getMainActivity().activityInfo.name));
        return true;
    }*/
}

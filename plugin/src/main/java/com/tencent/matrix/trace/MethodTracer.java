/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.matrix.trace;

import com.tencent.matrix.plugin.MatrixPlugin;
import com.tencent.matrix.javalib.util.FileUtil;
import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.javalib.util.Util;

import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Created by caichongyang on 2017/6/4.
 * <p>
 * This class hooks all collected methods in oder to trace method in/out.
 * </p>
 */

public class MethodTracer {

    private static final String TAG = "Matrix.MethodTracer";
    private static AtomicInteger traceMethodCount = new AtomicInteger();
    private final Configuration configuration;
    private final ConcurrentHashMap<String, String> collectedClassExtendMap;
    private final ExecutorService executor;

    private volatile boolean traceError = false;

    public MethodTracer(ExecutorService executor, Configuration config,  ConcurrentHashMap<String, String> collectedClassExtendMap) {
        this.configuration = config;

        this.executor = executor;
        this.collectedClassExtendMap = collectedClassExtendMap;

    }

    public void trace(Map<File, File> srcFolderList, Map<File, File> dependencyJarList, ClassLoader classLoader, boolean ignoreCheckClass) throws ExecutionException, InterruptedException {
        List<Future> futures = new LinkedList<>();
        traceMethodFromSrc(srcFolderList, futures, classLoader, ignoreCheckClass);
        traceMethodFromJar(dependencyJarList, futures, classLoader, ignoreCheckClass);
        for (Future future : futures) {
            future.get();
        }
        if (traceError) {
            throw new IllegalArgumentException("something wrong with trace, see detail log before");
        }
        futures.clear();
    }

    private void traceMethodFromSrc(Map<File, File> srcMap, List<Future> futures, final ClassLoader classLoader, final boolean skipCheckClass) {
        if (null != srcMap) {
            for (Map.Entry<File, File> entry : srcMap.entrySet()) {
                futures.add(executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        innerTraceMethodFromSrc(entry.getKey(), entry.getValue(), classLoader, skipCheckClass);
                    }
                }));
            }
        }
    }

    private void traceMethodFromJar(Map<File, File> dependencyMap, List<Future> futures, final ClassLoader classLoader, final boolean skipCheckClass) {
        if (null != dependencyMap) {
            for (Map.Entry<File, File> entry : dependencyMap.entrySet()) {
                futures.add(executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        innerTraceMethodFromJar(entry.getKey(), entry.getValue(), classLoader, skipCheckClass);
                    }
                }));
            }
        }
    }

    private void innerTraceMethodFromSrc(File input, File output, ClassLoader classLoader, boolean ignoreCheckClass) {

        ArrayList<File> classFileList = new ArrayList<>();
        if (input.isDirectory()) {
            listClassFiles(classFileList, input);
        } else {
            classFileList.add(input);
        }

        for (File classFile : classFileList) {
            InputStream is = null;
            FileOutputStream os = null;
            try {
                final String changedFileInputFullPath = classFile.getAbsolutePath();
                final File changedFileOutput = new File(changedFileInputFullPath.replace(input.getAbsolutePath(), output.getAbsolutePath()));

                if (changedFileOutput.getCanonicalPath().equals(classFile.getCanonicalPath())) {
                    throw new RuntimeException("Input file(" + classFile.getCanonicalPath() + ") should not be same with output!");
                }

                if (!changedFileOutput.exists()) {
                    changedFileOutput.getParentFile().mkdirs();
                }
                changedFileOutput.createNewFile();

                is = new FileInputStream(classFile);
                byte[] sourceBytes= IOUtils.toByteArray(is);

                if (MethodCollector.isNeedTraceFile(classFile.getName())) {

                    ArrayList<String> aopVisitors = MatrixPlugin.mMatrixExtension.getAopVisitors();
                    if(aopVisitors!=null&&!aopVisitors.isEmpty()){
                        for(String aopClassName:aopVisitors){
                            Class<?> aopClass = classLoader.loadClass(aopClassName);
                            Method modifyClassBytesMethod = aopClass.getMethod("modifyClassBytes", byte[].class);
                            sourceBytes = (byte[]) modifyClassBytesMethod.invoke(aopClass.newInstance(),sourceBytes);
                        }
                    }

                   // is = new FileInputStream(classFile);
//                    ClassReader classReader = new ClassReader(is);
//                    ClassWriter classWriter = new TraceClassWriter(ClassWriter.COMPUTE_FRAMES, classLoader);
//                    ClassVisitor classVisitor = new TraceClassAdapter(AgpCompat.getAsmApi(), classWriter);
//                    classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
//                    is.close();

                    byte[] data =sourceBytes;

                    if (!ignoreCheckClass) {
                        try {
                            ClassReader cr = new ClassReader(data);
                            ClassWriter cw = new ClassWriter(0);
                            ClassVisitor check = new CheckClassAdapter(cw);
                            cr.accept(check, ClassReader.EXPAND_FRAMES);
                        } catch (Throwable e) {
                            System.err.println("trace output ERROR : " + e.getMessage() + ", " + classFile);
                            traceError = true;
                        }
                    }

                    if (output.isDirectory()) {
                        os = new FileOutputStream(changedFileOutput);
                    } else {
                        os = new FileOutputStream(output);
                    }
                    os.write(data);
                    os.close();
                } else {
                    FileUtil.copyFileUsingStream(classFile, changedFileOutput);
                }
            } catch (Exception e) {
                Log.e(TAG, "[innerTraceMethodFromSrc] input:%s e:%s", input.getName(), e.getMessage());
                try {
                    Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            } finally {
                try {
                    is.close();
                    os.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private void innerTraceMethodFromJar(File input, File output, final ClassLoader classLoader, boolean skipCheckClass) {
        ZipOutputStream zipOutputStream = null;
        ZipFile zipFile = null;
        try {
            zipOutputStream = new ZipOutputStream(new FileOutputStream(output));
            zipFile = new ZipFile(input);
            Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                String zipEntryName = zipEntry.getName();

                if (Util.preventZipSlip(output, zipEntryName)) {
                    Log.e(TAG, "Unzip entry %s failed!", zipEntryName);
                    continue;
                }

                if (MethodCollector.isNeedTraceFile(zipEntryName)) {
                    InputStream inputStream = zipFile.getInputStream(zipEntry);

                    byte[] sourceBytes= IOUtils.toByteArray(inputStream);

                    ArrayList<String> aopVisitors = MatrixPlugin.mMatrixExtension.getAopVisitors();
                    if(aopVisitors!=null&&!aopVisitors.isEmpty()){
                        for(String aopClassName:aopVisitors){
                            Class<?> aopClass = classLoader.loadClass(aopClassName);
                            Method modifyClassBytesMethod = aopClass.getMethod("modifyClassBytes", byte[].class);
                            sourceBytes = (byte[]) modifyClassBytesMethod.invoke(aopClass.newInstance(),sourceBytes);
                        }
                    }

//                    ClassReader classReader = new ClassReader(inputStream);
//                    ClassWriter classWriter = new TraceClassWriter(ClassWriter.COMPUTE_FRAMES, classLoader);
//                    ClassVisitor classVisitor = new TraceClassAdapter(AgpCompat.getAsmApi(), classWriter);
//                    classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
                    byte[] data = sourceBytes;//classWriter.toByteArray();
//
                    if (!skipCheckClass) {
                        try {
                            ClassReader r = new ClassReader(data);
                            ClassWriter w = new ClassWriter(0);
                            ClassVisitor v = new CheckClassAdapter(w);
                            r.accept(v, ClassReader.EXPAND_FRAMES);
                        } catch (Throwable e) {
                            System.err.println("trace jar output ERROR: " + e.getMessage() + ", " + zipEntryName);
//                        e.printStackTrace();
                            traceError = true;
                        }
                    }

                    InputStream byteArrayInputStream = new ByteArrayInputStream(data);
                    ZipEntry newZipEntry = new ZipEntry(zipEntryName);
                    FileUtil.addZipEntry(zipOutputStream, newZipEntry, byteArrayInputStream);
                } else {
                    InputStream inputStream = zipFile.getInputStream(zipEntry);
                    ZipEntry newZipEntry = new ZipEntry(zipEntryName);
                    FileUtil.addZipEntry(zipOutputStream, newZipEntry, inputStream);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[innerTraceMethodFromJar] input:%s output:%s e:%s", input, output, e.getMessage());
            if (e instanceof ZipException) {
                e.printStackTrace();
            }
            try {
                if (input.length() > 0) {
                    Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Log.e(TAG, "[innerTraceMethodFromJar] input:%s is empty", input);
                }
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        } finally {
            try {
                if (zipOutputStream != null) {
                    zipOutputStream.finish();
                    zipOutputStream.flush();
                    zipOutputStream.close();
                }
                if (zipFile != null) {
                    zipFile.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "close stream err!");
            }
        }
    }

    private void listClassFiles(ArrayList<File> classFiles, File folder) {
        File[] files = folder.listFiles();
        if (null == files) {
            Log.e(TAG, "[listClassFiles] files is null! %s", folder.getAbsolutePath());
            return;
        }
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                listClassFiles(classFiles, file);
            } else {
                if (null != file && file.isFile()) {
                    classFiles.add(file);
                }

            }
        }
    }



}

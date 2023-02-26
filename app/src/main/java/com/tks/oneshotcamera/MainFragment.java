package com.tks.oneshotcamera;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainFragment extends Fragment {
    private FragmentActivity mActivity;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    private MainViewModel mViewModel;
    private final Semaphore mCameraOpenCloseSemaphore = new Semaphore(1);
    static ContentResolver mResolver;
    static final SimpleDateFormat mDf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.JAPAN);
    private ImageReader mImageReader;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest mRequestforPreview;
    private int mState = STATE_PREVIEW;

    /* 絞りを開ける=f値小, 光がたくさん, 被写界深度-浅, 背景がボケる。被写体を浮き立たせる効果がある。シャッター速度が速くなるため手ブレしにくくなる。 */
    /* 絞りを絞る　=f値大, 光が少し　　, 被写界深度-深, 画面全部にピントが合う。 */

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /*****************
     * onViewCreated()
     *****************/
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        /* メンバ初期化 */
        mActivity    = getActivity();
        if(mActivity == null) throw new RuntimeException("Error occurred!! illigal state in this app. activity is null!!");
        mTextureView = view.findViewById(R.id.tvw_preview);
        mViewModel   = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mResolver    = mActivity.getApplicationContext().getContentResolver();
        mDf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));

        /* カメラデバイスIDの確定と、そのCameraがサポートしている解像度リストを取得 */
        CameraManager manager = (CameraManager)mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for(String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                /* フロントカメラは対象外 */
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                /* streamConfig mapが取れなければ対象外 */
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map == null)
                    continue;

                Boolean flashavailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                mViewModel.setCameraId(cameraId);
                mViewModel.setSupportedCameraSizes(map.getOutputSizes(SurfaceTexture.class));
                mViewModel.setFlashSupported((flashavailable == null) ? false : flashavailable);
                break;
            }
        }
        catch(CameraAccessException e) {
            Log.d("aaaaa", e.toString());
            throw new RuntimeException("Error!! Camera is illigal state!!");
        }

        /* シャッターボタン押下 */
        view.findViewById(R.id.btn_shutter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dbglogout("takePicture");
                takePicture();
            }
        });
    }

    /*******************************************************************************************************
     * Handler初期化,TextureView初期化シーケンス
     * onResume() -> [onSurfaceTextureAvailable()] -> openCamera() CameraManager::openCamera() -> onOpened()
     *******************************************************************************************************/
    private TextureView mTextureView;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private CameraDevice mCameraDevice;

    /* onResume() -> [onSurfaceTextureAvailable()] -> openCamera() CameraManager::openCamera() -> onOpened() */
    /* ↑ココ                                                                                                  */
    @Override
    public void onResume() {
        super.onResume();

        /* start Handler */
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        if(mTextureView.isAvailable()) {
            openCamera(mViewModel.getCameraId());
        }
        else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    openCamera(mViewModel.getCameraId());
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
            });
        }
    }

    /* onResume() -> [onSurfaceTextureAvailable()] -> openCamera() -> CameraManager::openCamera() -> onOpened() */
    /*                                                ↑ ココ                                                   */
    private void openCamera(String cameraid) {
        /* 撮像(サイズと保存先)設定 */
        Size pictureSize = mViewModel.getTakePictureSize();
        mImageReader = ImageReader.newInstance(pictureSize.getWidth(), pictureSize.getHeight(), ImageFormat.JPEG, /*maxImages*/10);
        mImageReader.setOnImageAvailableListener(
                new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        /* ImageReaderからバイナリデータ取得 */
                        Image image = reader.acquireNextImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        image.close();
                        /* 画像保存処理を非同期で実行 */
                        mBackgroundHandler.post(new ImageSaver(bytes));
                    }
                },
                mBackgroundHandler);

        CameraManager manager = (CameraManager)mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if( !mCameraOpenCloseSemaphore.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw new RuntimeException("Time out waiting to lock camera opening.");

            /* 権限チェック -> 権限なし時はアプリ終了!!(CameraManager::openCamera()をコールする前には必ず必要) */
            if(ActivityCompat.checkSelfPermission(mActivity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                new Throwable().printStackTrace();
                ErrorDialog.newInstance(getString(R.string.request_permission)).show(getChildFragmentManager(), "Error!!");
            }
            manager.openCamera(cameraid, mDeviceStateCallback, mBackgroundHandler);
        }
        catch(InterruptedException | CameraAccessException e) {
            /* 異常が発生したら、例外吐いて終了 */
            throw new RuntimeException(e);
        }
    }

    /***************************************************************************************************************************************************
     * Preview開始シーケンス
     * CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed()
     ***************************************************************************************************************************************************/
    private final CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            /* This method is called when the camera is opened.  We start camera preview here. */
            mCameraOpenCloseSemaphore.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseSemaphore.release();
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    /* CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed() */
    /*                                            ↑ココ                                                                                                   */
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private void createCameraPreviewSession() {
        /******************************
         * TextureViewに歪み補正行列設定 */
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Size textureViewSize = (isLandscape) ? new Size(mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()) : new Size(mTextureView.getMeasuredHeight(), mTextureView.getMeasuredWidth());
        /* 画面サイズとカメラのSupportedサイズsから最適Previewサイズを求める */
        Size previewSize = getSuitablePreviewSize(mViewModel.getSupportedCameraSizes(), new Size(textureViewSize.getWidth(), textureViewSize.getHeight()));
        /* TextureViewとPreviewサイズのアスペクト比を求める */
        float textureViewAdpect= ((float)textureViewSize.getWidth())/textureViewSize.getHeight();
        float previewAdpect   = ((float)previewSize.getWidth())/previewSize.getHeight();
        dbglogout(String.format(Locale.JAPAN, "aaaaa(220)onResume() TextureViewサイズとアスペクト比(%s)[%f] Previewサイズとアスペクト比(%s)[%f]", textureViewSize, textureViewAdpect, previewSize, previewAdpect));
        /* TextureViewにPreviewサイズとのゆがみ補正行列を設定 */
        Matrix matrix = new Matrix();
        matrix.setScale(textureViewAdpect/previewAdpect, 1);  /* sony縦OK */
        mTextureView.setTransform(matrix);

        /**************************
         * カメラにPreviewサイズ設定 */
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        /* デフォルトバッファのサイズに、カメラPreviewのサイズを設定。 */
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

        /* SurfaceTexture -> Surface */
        Surface surface = new Surface(texture);

        /* We set up a CaptureRequest.Builder with the output Surface. */
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            /* カメラプレビュー用 CameraCaptureSessionを開始 */
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), mCaptureSessionStateCallback,null);
        }
        catch(CameraAccessException e) {
            /* 異常が発生したら、例外吐いて終了 */
            throw new RuntimeException(e);
        }
    }

    /*********************************************************************************
     * 最適Previewサイズ取得
     * 要は、baseSizeのサイズとアスペクト比に近い、Cameraサポート済サイズを取得している。
     **********************************************************************************/
    private Size getSuitablePreviewSize(Size[] supportedCameraSizes, Size baseSize) {
        /* baseサイズを求める */
        double baseArea  = ((double)baseSize.getWidth())*baseSize.getHeight();
        double baseAspect= ((double)baseSize.getWidth())/baseSize.getHeight();
        for(Size s : supportedCameraSizes)
            Log.d("aaaaa", String.format("aaaaa getSuitablePreviewSize() base=%s SupportedCameraSize=%s", baseSize, s));

        /* 正規化用パラメータを求める */
        Size maxPreviewSzie= Arrays.stream(supportedCameraSizes).max((o1, o2) -> {return o1.getWidth()*o1.getHeight() - o2.getWidth()*o2.getHeight();}).get();
        Size maxAspectSize = Arrays.stream(supportedCameraSizes).max((o1, o2) -> {return Double.compare(((double)o1.getWidth())/o1.getHeight(), ((double)o2.getWidth())/o2.getHeight());}).get();
        double maxAspect = ((double)maxAspectSize.getWidth()) / maxAspectSize.getHeight();

        Size suitableCameraPreviewSize = Arrays.stream(supportedCameraSizes).min((o1, o2) -> {
            /* 面積正規化 */
            double baseAreaNorm= baseArea / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
            double o1AreaNorm  = (((double)o1.getWidth())*o1.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());
            double o2AreaNorm  = (((double)o2.getWidth())*o2.getHeight()) / (((double)maxPreviewSzie.getWidth())*maxPreviewSzie.getHeight());

            /* アスペクト比正規化 */
            double baseAspectNorm= baseAspect / maxAspect;
            double o1AspectNorm  = (((double)o1.getWidth())/o1.getHeight()) / maxAspect;
            double o2AspectNorm  = (((double)o2.getWidth())/o2.getHeight()) / maxAspect;

            /* o1 */
            double o1AreaDiff     = o1AreaNorm   - baseAreaNorm;                        /* 面積差分 */
            double o1AspectDiff   = o1AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
            double o1MoreLargeDiff= (o1.getWidth()*o1.getHeight()== baseArea) ? 0.0 :
                                    (o1.getWidth()*o1.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */
            /* o2 */
            double o2AreaDiff     = o2AreaNorm   - baseAreaNorm;                        /* 面積差分 */
            double o2AspectDiff   = o2AspectNorm - baseAspectNorm;                      /* アスペクト比差分 */
            double o2MoreLargeDiff= (o2.getWidth()*o2.getHeight()== baseArea) ? 0.0 :
                                    (o2.getWidth()*o2.getHeight() < baseArea) ? 0.2 : 0.1;/* 基準サイズより大きい方を優位にする(小さい方に加算) */
            /* 特徴を一元化 */
            double o1Feature = Math.abs(o1AreaDiff) + Math.abs(o1AspectDiff) + Math.abs(o1MoreLargeDiff);
            double o2Feature = Math.abs(o2AreaDiff) + Math.abs(o2AspectDiff) + Math.abs(o2MoreLargeDiff);

            return Double.compare(o1Feature, o2Feature);
        }).get();

        Log.d("aaaaa",  String.format("aaaaa ちゃんとれたか？233 (%d x %d[%f])", suitableCameraPreviewSize.getWidth(), suitableCameraPreviewSize.getHeight(), ((double)suitableCameraPreviewSize.getWidth())/suitableCameraPreviewSize.getHeight()) );
        return suitableCameraPreviewSize;
    }

    /* CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed() */
    /*                                                                           ↑ココ                                                                                                              */
    CameraCaptureSession.StateCallback mCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            /* The camera is already closed */
            if (mCameraDevice ==null) return;

            /* When the session is ready, we start displaying the preview. */
            mCaptureSession = session;
            try {
                /* Auto focus should be continuous for camera preview. */
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                /* Flash is automatically enabled when necessary. */
                if(mViewModel.getFlashSupported())
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                /* Finally, we start displaying the camera preview. */
                mRequestforPreview = mPreviewRequestBuilder.build();
                mCaptureSession.setRepeatingRequest(mRequestforPreview, mCaptureCallback, mBackgroundHandler);
            }
            catch (CameraAccessException e) {
                /* 異常が発生したら、例外吐いて終了 */
                throw new RuntimeException(e);
            }
        }

        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { /* 異常が発生したら、例外吐いて終了 */ throw new RuntimeException(session.toString()); }
    };

    /* CameraDevice.StateCallback::onOpened() -> createCameraPreviewSession() -> StateCallback::onConfigured() -> CaptureCallback::onCaptureProgressed() */
    /*                                                                                                            ↑ココ                                  */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }

        /* STATE_PREVIEW ->  */
        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: /* 純粋プレビュー中 何もする必要なし */
                    break;
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if(afState == null) {
                        captureStillPicture();
                    }
                    else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        /* CONTROL_AE_STATE can be null on some devices */
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        }
                        else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    /* CONTROL_AE_STATE can be null on some devices */
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if(aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    /* CONTROL_AE_STATE can be null on some devices */
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if(aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }
    };

    /*****************
     * 撮像(シャッターON)
     *****************/
    private void takePicture() {
        dbglogout("takePicture s");
        try {
            /* This is how to tell the camera to lock focus. */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            /* Tell #mCaptureCallback to wait for the lock. */
            mState = STATE_WAITING_LOCK;
            dbglogout("takePicture capture(af-start) mCaptureCallback");
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("takePicture e");
    }

    /**
     * Run the pre-capture sequence for capturing a still image.
     * This method should be called when we get a response in {@link #mCaptureCallback} from {lockFocus()}.
     */
    //     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.

    private void runPrecaptureSequence() {
        dbglogout("s ");
        try{
            /* This is how to tell the camera to trigger. */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            /* Tell #mCaptureCallback to wait for the pre-capture sequence to be set. */
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("e ");
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {lockFocus()}.
     */
    private void captureStillPicture() {
        dbglogout("s ");
        dbglogout("takePicture captureStillPicture(STATE_WAITING_LOCK) s");
        try{
            final Activity activity = getActivity();
            if(null == activity || null == mCameraDevice) return;

            /* This is the CaptureRequest.Builder that we use to take a picture. */
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            /* Use the same AE and AF modes as the preview. */
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if(mViewModel.getFlashSupported())
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            /* Orientation これは設定せん。なんか動かんらしい。  */
//            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
//            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360);
            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    dbglogout("s onCaptureCompleted(239)");
                    dbglogout("takePicture onCaptureCompleted() -> unlockFocus()");
                    unlockFocus();
                    dbglogout("e onCaptureCompleted(243)");
                }
            };

            dbglogout("takePicture CaptureSession::stopRepeating()");
            mCaptureSession.stopRepeating();
            dbglogout("takePicture CaptureSession::abortCaptures()");
            mCaptureSession.abortCaptures();
            dbglogout("takePicture CaptureSession::capture(ImageSever, CaptureCallback)");
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("e ");
        dbglogout("takePicture captureStillPicture(STATE_WAITING_LOCK) e");
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        dbglogout("s ");
        dbglogout("takePicture unlockFocus() s");
        try {
            /* Reset the auto-focus trigger */
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            if(mViewModel.getFlashSupported())
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            /* After this, the camera will go back to the normal state of preview. */
            dbglogout("takePicture status is set a STATE_PREVIEW.");
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mRequestforPreview, mCaptureCallback, mBackgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        dbglogout("takePicture unlockFocus() e");
        dbglogout("e ");
    }

    /**********************************************************************
     * Handler終了,TextureView終了シーケンス
     **********************************************************************/
    @Override
    public void onPause() {
        super.onPause();

        /* stop Camera */
        closeCamera();

        /* stop Handler */
        mBackgroundThread.quitSafely();
        try {
            mBackgroundHandler.getLooper().getThread().join();
            mBackgroundHandler = null;
        }
        catch(InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /*****************
     * Close Camera */
    private void closeCamera() {
        try {
            mCameraOpenCloseSemaphore.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        finally {
            mCameraOpenCloseSemaphore.release();
        }
    }

    private void showToast(final String text) {
        final Activity activity = getActivity();
        if(activity == null)
            return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * ImageSaver class
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {
        private final byte[] mBytes;
        ImageSaver(byte[] bytes) {
            mBytes = bytes;
        }

        @Override
        public void run() {
            ContentValues values = new ContentValues();
            /* ファイル名 */
            values.put(MediaStore.Images.Media.DISPLAY_NAME, String.format("aaaaa_%s.jpg", mDf.format(new Date())));
            /* マイムの設定 */
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            /* 書込み時にメディアファイルに排他アクセスする */
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            /* コンテンツ管理領域に、Picture領域を予約 */
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = mResolver.insert(collection, values);

            /* Picture領域に書込み */
            OutputStream outstream = null;
            try {
                outstream = mResolver.openOutputStream(item);
                outstream.write(mBytes);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            finally {
            /* 終了処理 */
                values.clear();
                /*　排他アクセスの解除 */
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                mResolver.update(item, values, null, null);
                if (outstream != null) {
                    try {
                        outstream.close();
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * ErrorDialog class
     */
    private static final String FRAGMENT_DIALOG = "dialog";
    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";
        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            final Activity activity = getActivity();
            if(activity == null) throw new RuntimeException("illegal state!! activity is null!!");
            android.os.Bundle bundle = getArguments();
            if(bundle == null) throw new RuntimeException("illegal state!! bundle is null!!");

            return new AlertDialog.Builder(activity)
                    .setMessage(bundle.getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    private static void dbglogout(String msg) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String className = stack[1].getClassName();
        String method = stack[1].getMethodName();
        int line = stack[1].getLineNumber();
        StringBuilder buf = new StringBuilder(60);
        buf.append(msg).append(" ").append(className).append("::").append(method).append("(").append(line).append(")");
        Log.d("aaaaa", buf.toString());
    }
}

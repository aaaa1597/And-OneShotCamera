package com.tks.oneshotcamera;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

public class MainFragment extends Fragment {

    private MainViewModel mViewModel;
    private AutoFitTextureView mTextureView;
    private Handler mBackgroundHandler;

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTextureView = view.findViewById(R.id.tvw_picture);
        mViewModel = new ViewModelProvider(this).get(MainViewModel.class);
        // TODO: Use the ViewModel
    }

    @Override
    public void onResume() {
        Log.d("aaaaa", "onResume()");
        super.onResume();

        /* start Handler */
        HandlerThread t = new HandlerThread("CameraBackground");
        t.start();
        mBackgroundHandler = new Handler(t.getLooper());

        if(mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        }
        else {
            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Log.d("aaaaa", "??? 1st onSurfaceTextureAvailable()");
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    Log.d("aaaaa", "??? 2nd onSurfaceTextureSizeChanged()");
//                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    Log.d("aaaaa", "??? 3rd onSurfaceTextureDestroyed()");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                    Log.d("aaaaa", "??? 4th onSurfaceTextureUpdated()");
                }
            });
        }
    }

    @Override
    public void onPause() {
        Log.d("aaaaa", "onPause()");
        super.onPause();

        /* stop Handler */
        try {
            mBackgroundHandler.getLooper().getThread().join();
            mBackgroundHandler = null;
        }
        catch (InterruptedException e) { throw new RuntimeException(e); }

    }

    private void openCamera(int width, int height) {
        Log.d("aaaaa", "openCamera() w=" + width + " h=" + height);
//        if (ContextCompat.checkSelfPermission(getActivity(), android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//            Log.d("aaaaa", "openCamera() ...");
//            if (shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
//                Log.d("aaaaa", "openCamera() ...2");
//                new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
//            }
//            else {
//                Log.d("aaaaa", "openCamera() ...3");
//                /* TODO aaaaaaaaaaaa */
//                ActivityResultLauncher<String> launcher
//                        = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
//                        isGranted  -> {
//                            if (isGranted) {
//                                Toast.makeText(getContext(), "aaaaa!!!!!", Toast.LENGTH_LONG).show();
//                            }
//                            else {
//                                ErrorDialog.newInstance(getString(R.string.request_permission))
//                                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
//                            }
//                        });
//                launcher.launch(android.Manifest.permission.CAMERA);
////                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
//            }
//        }

    }

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
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    public static class ConfirmationDialog extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityResultLauncher<String> launcher
                                    = parent.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                                                                        isGranted  -> {
                                                                            if (isGranted) {
                                                                                Toast.makeText(getContext(), "aaaaa!!!!!", Toast.LENGTH_LONG).show();
                                                                            }
                                                                            else {
                                                                                ErrorDialog.newInstance(getString(R.string.request_permission))
                                                                                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                                                                            }
                                                                        });
                            launcher.launch(android.Manifest.permission.CAMERA);
//                            parent.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Activity activity = parent.getActivity();
                            if(activity != null)
                                activity.finish();
                        }
                    })
                    .create();
        }
    }
}

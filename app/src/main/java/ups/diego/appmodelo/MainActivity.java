package ups.diego.appmodelo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ups.diego.appmodelo.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'appmodelo' library on application startup.
    static {
        System.loadLibrary("appmodelo");
    }

    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{android.Manifest.permission.CAMERA};


    private ActivityMainBinding binding;
    private android.widget.ImageView imageView;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        imageView = findViewById(R.id.imageViewDetections);

        // Example of a call to a native method
        TextView tv = binding.sampleText;
        tv.setText(stringFromJNI());

        // Verifica permisos y arranca la cámara
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Vista previa de la cámara
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.camara.getSurfaceProvider());

                // Análisis de imágenes
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    Bitmap bitmap = convertImageProxyToBitmap(image);
                    //runOnUiThread(() -> imageView.setImageBitmap(bitmap));

                    // Corrige la orientación usando los metadatos del ImageProxy
                    int rotationDegrees = image.getImageInfo().getRotationDegrees();
                    Bitmap rotatedBitmap = rotateBitmap(bitmap, rotationDegrees);

                    runOnUiThread(() -> imageView.setImageBitmap(rotatedBitmap));

                    System.out.println("Bitmap dimensions: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    System.out.println("ImageView dimensions: " + imageView.getWidth() + "x" + imageView.getHeight());

                    image.close();
                });

                // Selección de cámara (trasera o frontal)
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                //CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                // Enlaza la cámara al ciclo de vida
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private Bitmap convertImageProxyToBitmap(ImageProxy image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Luma
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // Chroma U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // Chroma V

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // Copia los datos del buffer YUV al formato NV21
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
        byte[] jpegBytes = out.toByteArray();

        // Decodifica los bytes JPEG en un Bitmap
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Los permisos no se concedieron.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }


    /**
     * A native method that is implemented by the 'appmodelo' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
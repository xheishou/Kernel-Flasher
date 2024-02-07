package com.xheishou.kernelflasher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int FILE_SELECT_CODE = 0;
    private static final int PERMISSION_REQUEST_STORAGE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnFlashKernel = findViewById(R.id.btnFlashKernel);
        btnFlashKernel.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
						ActivityCompat.requestPermissions(MainActivity.this,
														  new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
														  PERMISSION_REQUEST_STORAGE);
					} else {
						showFileChooser();
					}
				}
			});
    }

    private void showFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*"); // 设置文件类型为任意类型
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(
				Intent.createChooser(intent, "Select a File to Upload"),
				FILE_SELECT_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a File Manager.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showFileChooser();
            } else {
                showToast("Permission denied.");
            }
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK) {
            // Get the Uri of the selected file
            Uri uri = data.getData();
            flashKernel(uri);
        }
    }

    private void flashKernel(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                File dir = getDir("temp", Context.MODE_PRIVATE);
                String dirPath = dir.getAbsolutePath();

                // 判断如果是zip文件则解压缩，否则直接复制到临时目录
                String fileName = getFileNameFromUri(uri);
                if (fileName.endsWith(".zip")) {
                    ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                    ZipEntry entry;
                    while ((entry = zipInputStream.getNextEntry()) != null) {
                        String entryName = entry.getName();
                        if (entry.isDirectory()) {
                            // 如果是目录则创建对应目录
                            File subDir = new File(dirPath + File.separator + entryName);
                            subDir.mkdir();
                        } else {
                            // 如果是文件则写入到临时文件中
                            File file = new File(dirPath + File.separator + entryName);
                            FileOutputStream outputStream = new FileOutputStream(file);
                            byte[] buffer = new byte[1024];
                            int length;
                            while ((length = zipInputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, length);
                            }
                            outputStream.close();
                        }
                    }

                    zipInputStream.close();
                } else {
                    File destFile = new File(dirPath + File.separator + fileName);
                    copyToFile(inputStream, destFile);
                }

                // 获取anykernel.sh和Image.gz-dtb或Image.gz文件
                File anykernelFile = new File(dirPath + File.separator + "anykernel.sh");
                File imageFile = new File(dirPath + File.separator + "Image.gz-dtb");
                if (!imageFile.exists()) {
                    imageFile = new File(dirPath + File.separator + "Image.gz");
                }

                if (anykernelFile.exists() && imageFile.exists()) {
                    String[] commands = {
						"su",
						"sh " + anykernelFile.getAbsolutePath() + " " + imageFile.getAbsolutePath()
                    };
                    Process process = Runtime.getRuntime().exec(commands);
                    process.waitFor();
                    if (process.exitValue() == 0) {
                        showToast("Kernel flashed successfully!");
                    } else {
                        showToast("Failed to flash kernel.");
                    }
                } else {
                    showToast("Invalid kernel package.");
                }

                // 删除临时文件和目录
                deleteFileOrDirectory(dir);
            }
        } catch (Exception e) {
            e.printStackTrace();
            showToast("Error flashing kernel: " + e.getMessage());
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index != -1) {
                    result = cursor.getString(index);
                }
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void copyToFile(InputStream inputStream, File destFile) throws IOException {
        OutputStream outputStream = new FileOutputStream(destFile);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }
        outputStream.flush();
        outputStream.close();
        inputStream.close();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void deleteFileOrDirectory(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File subFile : files) {
                    deleteFileOrDirectory(subFile);
                }
            }
        }
        file.delete();
    }

}


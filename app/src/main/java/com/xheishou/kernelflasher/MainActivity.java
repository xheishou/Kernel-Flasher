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
import java.io.BufferedReader;
import java.io.InputStreamReader;

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
				String fileName = getFileNameFromUri(uri);
				File dir = getDir("temp", Context.MODE_PRIVATE);
				String dirPath = dir.getAbsolutePath();

				// 判断文件类型
				if (fileName.endsWith(".zip")) {
					// 如果是ZIP文件，解压缩到临时目录
					ZipInputStream zipInputStream = new ZipInputStream(inputStream);
					extractZip(zipInputStream, dirPath);
					zipInputStream.close();

					// 获取anykernel.sh和Image.gz-dtb或Image.gz文件
					File anykernelFile = new File(dirPath + File.separator + "anykernel.sh");
					File imageFile = new File(dirPath + File.separator + "Image.gz-dtb");
					if (!imageFile.exists()) {
						imageFile = new File(dirPath + File.separator + "Image.gz");
					}

					if (anykernelFile.exists() && imageFile.exists()) {
						// 使用su命令刷入内核
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
				} else if (fileName.endsWith(".img")) {
					// 如果是IMG文件，使用fastboot命令刷入
					File destFile = new File(dirPath + File.separator + fileName);
					copyToFile(inputStream, destFile);

					// 检查设备是否支持fastboot
					Process process = Runtime.getRuntime().exec("adb devices");
					String output = new String();
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					String line;
					while ((line = reader.readLine()) != null) {
						output += line;
					}
					reader.close();
					if (!output.contains("fastboot")) {
						showToast("Fastboot is not supported on this device.");
						return;
					}

					// 执行fastboot命令刷入IMG文件
					String command = "adb reboot bootloader && adb flash boot " + destFile.getAbsolutePath();
					process = Runtime.getRuntime().exec(command);
					int exitValue = process.waitFor();
					if (exitValue == 0) {
						showToast("Kernel flashed successfully!");
					} else {
						showToast("Failed to flash kernel.");
					}
				} else {
					showToast("Unsupported file format.");
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			showToast("Error flashing kernel: " + e.getMessage());
		}
	}

// 添加的辅助方法，用于解压缩ZIP文件
	private void extractZip(ZipInputStream zipInputStream, String dirPath) throws IOException {
		ZipEntry entry;
		while ((entry = zipInputStream.getNextEntry()) != null) {
			String entryName = entry.getName();
			if (entry.isDirectory()) {
				File subDir = new File(dirPath + File.separator + entryName);
				subDir.mkdir();
			} else {
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


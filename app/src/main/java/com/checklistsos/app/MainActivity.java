package com.checklistsos.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Environment;
import android.database.Cursor;
import android.content.ClipData;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.widget.Toast;

public class MainActivity extends Activity {
    private ValueCallback<Uri[]> uploadMessage;
    private Uri cameraUri;
    private final ArrayList<Uri> fotosCapturadas = new ArrayList<>();
    private volatile boolean categoriaInterna = false;
    private volatile String placaAtual = "SEM_PLACA";
    private String pastaDaPlaca(String placa) {
        String nome = (placa == null ? "" : placa.trim().toUpperCase(java.util.Locale.ROOT))
            .replaceAll("[^A-Z0-9_-]", "_");
        if (nome.isEmpty()) nome = "SEM_PLACA";
        return Environment.DIRECTORY_PICTURES + "/CheckListSOS/" + nome + "/";
    }


    public class Ponte {
        @JavascriptInterface
        public void setCategoriaFoto(int indice) {
            categoriaInterna = (indice == 4);
        }

        @JavascriptInterface
        public void setPlaca(String placa) {
            placaAtual = (placa == null ? "" : placa.trim().toUpperCase(java.util.Locale.ROOT)).replaceAll("[^A-Z0-9_-]", "_");
            if (placaAtual.isEmpty()) placaAtual = "SEM_PLACA";
        }

        @JavascriptInterface
        public String listarFotos(String placa) {
            JSONArray lista = new JSONArray();
            String pasta = pastaDaPlaca(placa);
            String[] colunas = {MediaStore.Images.Media._ID};
            try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                colunas,
                MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[]{pasta},
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        Uri uri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id));
                        lista.put(uri.toString());
                    }
                }
            }
            return lista.toString();
        }

        @JavascriptInterface
        public void compartilharFotos(String placa, String fichaJson, String guincheiroJson) {
            String descricao = (placa == null ? "" : placa.trim().toUpperCase(java.util.Locale.ROOT));
            String pasta = pastaDaPlaca(descricao);
            ArrayList<Uri> fotos = new ArrayList<>();

            try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[]{pasta},
                null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        fotos.add(Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(c.getLong(0))));
                    }
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Erro ao localizar fotos", Toast.LENGTH_LONG).show();
                return;
            }

            if (fotos.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Nenhuma foto encontrada para este veículo", Toast.LENGTH_LONG).show());
                return;
            }

            try {
                File pastaZip = new File(getCacheDir(), "shared");
                if (!pastaZip.exists()) pastaZip.mkdirs();
                String nomeSeguro = descricao.replaceAll("[^A-Z0-9_-]", "_");
                if (nomeSeguro.isEmpty()) nomeSeguro = "SEM_PLACA";
                File arquivoZip = new File(pastaZip, "Fotos_" + nomeSeguro + ".zip");

                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(arquivoZip))) {
                    JSONObject ficha = new JSONObject(fichaJson == null ? "{}" : fichaJson);
                    JSONObject guincheiro = new JSONObject(guincheiroJson == null ? "{}" : guincheiroJson);

                    StringBuilder texto = new StringBuilder();
                    texto.append("DADOS DO VEICULO\n");
                    texto.append("Placa: ").append(descricao).append("\n\n");
                    texto.append("DADOS DO TRANSPORTE\n");
                    texto.append("Cliente: ").append(ficha.optString("cliente", "")).append("\n");
                    texto.append("Celular: ").append(ficha.optString("celular", "")).append("\n");
                    texto.append("Origem: ").append(ficha.optString("origem", "")).append("\n");
                    texto.append("Destino: ").append(ficha.optString("destino", "")).append("\n\n");
                    texto.append("DADOS DO GUINCHEIRO\n");
                    texto.append("Nome: ").append(guincheiro.optString("nome", "")).append("\n");
                    texto.append("CPF: ").append(guincheiro.optString("cpf", "")).append("\n");
                    texto.append("Telefone: ").append(guincheiro.optString("telefone", "")).append("\n");
                    texto.append("CNH: ").append(guincheiro.optString("cnh", "")).append("\n");
                    texto.append("Placa do guincho: ").append(guincheiro.optString("placa", "")).append("\n");
                    texto.append("Modelo do guincho: ").append(guincheiro.optString("modelo", "")).append("\n");

                    zip.putNextEntry(new ZipEntry("DESCRICAO.txt"));
                    zip.write(texto.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();

                    byte[] buffer = new byte[8192];
                    for (int i = 0; i < fotos.size(); i++) {
                        zip.putNextEntry(new ZipEntry("Foto_" + (i + 1) + ".jpg"));
                        try (InputStream entrada = getContentResolver().openInputStream(fotos.get(i))) {
                            if (entrada == null) throw new Exception("Não foi possível abrir uma foto");
                            int lido;
                            while ((lido = entrada.read(buffer)) != -1) {
                                zip.write(buffer, 0, lido);
                            }
                        }
                        zip.closeEntry();
                    }
                }

                Uri zipUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    arquivoZip
                );

                Intent enviar = new Intent(Intent.ACTION_SEND);
                enviar.setType("application/zip");
                enviar.putExtra(Intent.EXTRA_STREAM, zipUri);
                enviar.putExtra(Intent.EXTRA_SUBJECT, "Fotos do veículo " + descricao);
                enviar.putExtra(Intent.EXTRA_TEXT, "Veículo: " + descricao);
                enviar.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                enviar.setClipData(ClipData.newUri(getContentResolver(), "Arquivo ZIP", zipUri));

                runOnUiThread(() -> startActivity(
                    Intent.createChooser(enviar, "Enviar ZIP do veículo")));

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Erro ao criar ZIP: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
        @JavascriptInterface
        public int excluirFotos(String placa) {
            String pasta = pastaDaPlaca(placa);
            return getContentResolver().delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[]{pasta}
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
settings.setSupportZoom(false);
settings.setBuiltInZoomControls(false);
settings.setDisplayZoomControls(false);
        web.addJavascriptInterface(new Ponte(), "Android");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadMessage != null) uploadMessage.onReceiveValue(null);
                uploadMessage = callback;
                fotosCapturadas.clear();
                try {
                    abrirCameraSOS();
                    return true;
                } catch (Exception e) {
                    finalizarCapturaSOS();
                    return false;
                }
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void abrirCameraSOS() throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Foto_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, pastaDaPlaca(placaAtual));
        cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (cameraUri == null) throw new Exception("Erro ao criar foto");

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        intent.setClipData(ClipData.newRawUri("foto", cameraUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, 100);
    }

    private void finalizarCapturaSOS() {
        if (uploadMessage != null) {
            Uri[] resultado = fotosCapturadas.isEmpty() ? null : fotosCapturadas.toArray(new Uri[0]);
            uploadMessage.onReceiveValue(resultado);
            uploadMessage = null;
        }
        cameraUri = null;
        fotosCapturadas.clear();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 100 || uploadMessage == null) return;

        if (resultCode == RESULT_OK && cameraUri != null) {
            ContentValues finalizado = new ContentValues();
            finalizado.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(cameraUri, finalizado, null, null);
            fotosCapturadas.add(cameraUri);
        } else if (cameraUri != null) {
            getContentResolver().delete(cameraUri, null, null);
        }
        cameraUri = null;

        if (resultCode == RESULT_OK && categoriaInterna) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Fotos internas")
                .setMessage("Deseja tirar outra foto?")
                .setPositiveButton("Tirar outra", (dialog, which) -> {
                    try {
                        abrirCameraSOS();
                    } catch (Exception e) {
                        finalizarCapturaSOS();
                    }
                })
                .setNegativeButton("Concluir", (dialog, which) -> finalizarCapturaSOS())
                .setCancelable(false)
                .show();
        } else {
            finalizarCapturaSOS();
        }
    }

}

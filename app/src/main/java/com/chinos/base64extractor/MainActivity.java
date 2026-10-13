package com.chinos.base64extractor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private final int INPUT = 100, OUTPUT = 101;
    private final Pattern audioPattern = Pattern.compile("data:audio/(mpeg|mp3|wav|x-wav|ogg|webm|mp4|aac);base64,([A-Za-z0-9+/=\\s]+)", Pattern.CASE_INSENSITIVE);
    private Uri inputUri, folderUri;
    private TextView chosen, folder, status;
    private Button extract;
    private ProgressBar progress;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int px(int dp) {return (int)(getResources().getDisplayMetrics().density * dp);}
    private TextView text(LinearLayout parent, String s, int size) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(23,51,52));
        v.setPadding(0,px(10),0,px(10)); parent.addView(v); return v;
    }
    private Button button(LinearLayout parent, String s) {
        Button b = new Button(this); b.setText(s); parent.addView(b); return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(24),px(28),px(24),px(24)); root.setBackgroundColor(Color.rgb(247,244,236));
        scroll.addView(root);
        text(root,"EXTRACTOR DE AUDIOS BASE64",25);
        text(root,"Selecciona un HTML con audios incorporados y una carpeta donde guardarlos. No necesita internet.",15);
        Button select = button(root,"1. SELECCIONAR HTML");
        chosen = text(root,"Ningún HTML seleccionado",14);
        Button destination = button(root,"2. ELEGIR CARPETA DE DESTINO");
        folder = text(root,"Ninguna carpeta seleccionada",14);
        extract = button(root,"3. EXTRAER AUDIOS"); extract.setEnabled(false);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setVisibility(View.GONE); root.addView(progress);
        status = text(root,"Listo para extraer MP3, WAV, OGG, WebM, M4A o AAC.",15);
        select.setOnClickListener(v -> {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i,INPUT);
        });
        destination.setOnClickListener(v -> {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i,OUTPUT);
        });
        extract.setOnClickListener(v -> doExtract());
        setContentView(scroll);
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(code==INPUT){inputUri=data.getData();chosen.setText("HTML: "+inputUri.getLastPathSegment());}
        if(code==OUTPUT){folderUri=data.getData();folder.setText("Destino: "+folderUri.getLastPathSegment());}
        extract.setEnabled(inputUri!=null&&folderUri!=null);
    }
    private String extension(String s) {
        if(s.equals("mpeg")||s.equals("mp3"))return "mp3";
        if(s.equals("x-wav"))return "wav";
        if(s.equals("mp4"))return "m4a";
        return s;
    }
    private String mime(String e) {
        if(e.equals("mp3"))return "audio/mpeg";
        if(e.equals("m4a"))return "audio/mp4";
        return "audio/"+e;
    }
    private void complete(String result) {
        ui.post(()->{status.setText(result);progress.setVisibility(View.GONE);extract.setEnabled(true);});
    }
    private void doExtract() {
        extract.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setProgress(0);
        status.setText("Extrayendo...");
        final Uri source=inputUri, tree=folderUri;
        new Thread(()->{
            try {
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                try(InputStream in=getContentResolver().openInputStream(source)){
                    if(in==null)throw new Exception("No se pudo leer el HTML.");
                    byte[] chunk=new byte[8192];int n;
                    while((n=in.read(chunk))!=-1){
                        buffer.write(chunk,0,n);
                        if(buffer.size()>80_000_000)throw new Exception("El HTML supera 80 MB.");
                    }
                }
                Matcher m=audioPattern.matcher(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
                String documentId=DocumentsContract.getTreeDocumentId(tree);
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,documentId);
                Set<String> existing=new HashSet<>();
                try(android.database.Cursor c=getContentResolver().query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree,documentId),
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                    if(c!=null)while(c.moveToNext())existing.add(c.getString(0));
                }catch(Exception ignored){}
                int found=0,saved=0,errors=0;
                while(m.find()){
                    found++;String ext=extension(m.group(1).toLowerCase(Locale.ROOT));
                    String name=String.format(Locale.ROOT,"%02d.%s",found,ext);
                    if(existing.contains(name)){
                        int k=2;String candidate;
                        do{candidate=String.format(Locale.ROOT,"%02d_%d.%s",found,k++,ext);}while(existing.contains(candidate));
                        name=candidate;
                    }
                    try {
                        byte[] bytes=Base64.decode(m.group(2).replaceAll("\\s+",""),Base64.DEFAULT);
                        if(bytes.length==0)throw new Exception("Audio vacío");
                        Uri output=DocumentsContract.createDocument(getContentResolver(),parent,mime(ext),name);
                        if(output==null)throw new Exception("Error al crear archivo");
                        try(OutputStream out=getContentResolver().openOutputStream(output,"w")){
                            if(out==null)throw new Exception("Error de escritura");
                            out.write(bytes);
                        }
                        existing.add(name);saved++;
                    }catch(Exception e){errors++;}
                    final int done=found,ok=saved;
                    ui.post(()->{progress.setProgress(Math.min(99,done));status.setText("Procesados: "+done+" · Guardados: "+ok);});
                }
                if(found==0)complete("No se encontraron audios Base64 dentro del HTML.");
                else complete("Finalizado. Encontrados: "+found+" · Guardados: "+saved+" · Errores: "+errors);
            }catch(Exception e){complete("Error: "+e.getMessage());}
        }).start();
    }
}

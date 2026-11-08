package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Extracted content is original text only; no OCR, model or embedding calls. */
@Component
public class CourseFileParser {
    public static final long MAX_BYTES=20L*1024*1024;
    public record Upload(String filename,String type,String sha256) { }
    public record Chunk(int index,Integer pageNo,String content) { }
    public static final class ParseFailure extends RuntimeException {
        private final String code;
        public ParseFailure(String code,String message) {super(message);this.code=code;}
        public String code() {return code;}
    }
    public Upload validate(MultipartFile file) throws IOException {
        if(file.isEmpty()) throw new ApiException(42205,"文件不能为空");
        if(file.getSize()>MAX_BYTES) throw new ApiException(42203,"单文件不能超过 20 MB");
        String name=file.getOriginalFilename();
        if(name==null || name.codePoints().anyMatch(c->c<32 || c==127)) throw ApiException.invalid();
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        name=CourseInputs.text(name,255);
        int dot=name.lastIndexOf('.');String ext=dot<1?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!Set.of("pdf","txt","docx","pptx").contains(ext)) throw unsupported();
        String mime=file.getContentType();
        mime=mime==null?"application/octet-stream":mime.split(";",2)[0].strip().toLowerCase(Locale.ROOT);
        if(!mime.equals("application/octet-stream") && !mime.equals(mime(ext.toUpperCase(Locale.ROOT))))
            throw unsupported();
        byte[] bytes=file.getBytes();
        if(bytes.length>MAX_BYTES) throw new ApiException(42203,"单文件不能超过 20 MB");
        if(ext.equals("txt")) decodeText(bytes);
        else if(Set.of("docx","pptx").contains(ext)) CourseOfficeParser.validate(bytes,ext.toUpperCase(Locale.ROOT));
        else if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw unsupported();
        return new Upload(name,ext.toUpperCase(Locale.ROOT),sha256(bytes));
    }
    static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 unavailable",e);}
    }
    public static String mime(String type) {
        return switch(type) {
            case "PDF" -> "application/pdf";
            case "TXT" -> "text/plain";
            case "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "PPTX" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }
    private ApiException unsupported() {return new ApiException(42202,"仅支持内容匹配的 PDF、UTF-8 TXT、DOCX 和 PPTX");}
    private String decodeText(byte[] bytes) {
        if(bytes.length>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw unsupported();
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e) {throw new ApiException(42204,"TXT 必须使用 UTF-8 编码");}
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        if(text.startsWith("%PDF-") || text.codePoints().anyMatch(c->(c<32 && c!=9 && c!=10 && c!=13)||c==127)) throw unsupported();
        return text;
    }
    public List<Chunk> parse(byte[] bytes,String type) {
        List<Chunk> chunks=new ArrayList<>();
        if("TXT".equals(type)) {
            try {append(chunks,decodeText(bytes),null);}
            catch(ApiException e) {throw new ParseFailure("PARSE_ERROR","TXT 内容无法解析，请重新上传 UTF-8 文件");}
        } else if("PDF".equals(type)) {
            try(var pdf=Loader.loadPDF(bytes)) {
                if(pdf.isEncrypted() && !pdf.getCurrentAccessPermission().canExtractContent())
                    throw new ParseFailure("PDF_PARSE_ERROR","PDF 不允许提取文本，请上传可读版本");
                PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                for(int page=1;page<=pdf.getNumberOfPages();page++) {
                    stripper.setStartPage(page);stripper.setEndPage(page);append(chunks,stripper.getText(pdf),page);
                }
            } catch(IOException e) {throw new ParseFailure("PDF_PARSE_ERROR","PDF 损坏、加密或不可读取，请上传可读版本");}
        } else if(Set.of("DOCX","PPTX").contains(type)) {
            for(var text:CourseOfficeParser.read(bytes,type)) append(chunks,text.content(),text.pageNo());
        } else throw new ParseFailure("PARSE_ERROR","文件格式无法解析");
        if(chunks.isEmpty()) throw new ParseFailure("EMPTY_TEXT","未提取到文字；图片和扫描件暂不支持OCR，请上传含可提取文字的资料");
        return chunks;
    }
    private void append(List<Chunk> chunks,String text,Integer page) {
        if(text.isBlank()) return;
        int[] points=text.codePoints().toArray();
        for(int start=0;start<points.length;start+=700) {
            int end=Math.min(start+800,points.length);
            String part=new String(points,start,end-start);
            if(!part.isBlank()) chunks.add(new Chunk(chunks.size()+1,page,part));
            if(end==points.length) break;
        }
    }
}

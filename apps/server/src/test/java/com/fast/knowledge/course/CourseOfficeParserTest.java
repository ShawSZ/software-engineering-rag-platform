package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class CourseOfficeParserTest {
    final CourseFileParser parser=new CourseFileParser();
    MockMultipartFile file(String name,String mime,byte[] bytes) {return new MockMultipartFile("file",name,mime,bytes);}
    byte[] archive(String main,String xml,String extra,byte[] payload) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)) {
            for(var item:Map.of("[Content_Types].xml","<Types/>",main,xml).entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            if(extra!=null) {zip.putNextEntry(new ZipEntry(extra));zip.write(payload);zip.closeEntry();}
        }
        return out.toByteArray();
    }
    @Test void wordParagraphsTablesUnicodeAndChunksKeepOriginalTextWithoutInventedPages() throws Exception {
        String source="😀课程测试".repeat(450);byte[] bytes=CourseFixtures.docx(source);
        assertEquals("DOCX",parser.validate(file("课程.DOCX",CourseFileParser.mime("DOCX"),bytes)).type());
        assertEquals("DOCX",parser.validate(file("课程.docx","application/octet-stream",bytes)).type());
        var chunks=parser.parse(bytes,"DOCX");assertTrue(chunks.size()>2);
        for(int i=0;i<chunks.size();i++) {
            var chunk=chunks.get(i);assertEquals(i+1,chunk.index());assertNull(chunk.pageNo());
            assertTrue(chunk.content().codePointCount(0,chunk.content().length())<=800);
            if(i+1<chunks.size()) {
                var next=chunks.get(i+1).content();String text=chunk.content();
                assertEquals(text.substring(text.offsetByCodePoints(0,700)),next.substring(0,next.offsetByCodePoints(0,100)));
            }
        }
        String extracted=CourseOfficeParser.read(bytes,"DOCX").getFirst().content();
        assertTrue(extracted.startsWith(source));assertTrue(extracted.contains("单元测试\n\t检查独立模块的行为"));
    }
    @Test void presentationRetainsSlideNumbersIncludingBlankSlidesTablesAndGroupedShapes() throws Exception {
        byte[] bytes=CourseFixtures.pptx("需求分析原文","","软件测试原文");
        assertEquals("PPTX",parser.validate(file("课件.pptx",CourseFileParser.mime("PPTX"),bytes)).type());
        var chunks=parser.parse(bytes,"PPTX");assertEquals(2,chunks.size());
        assertEquals(1,chunks.get(0).pageNo());assertEquals(3,chunks.get(1).pageNo());
        assertTrue(chunks.get(0).content().contains("需求分析原文"));assertFalse(chunks.get(0).content().contains("软件测试原文"));
        assertTrue(chunks.get(1).content().contains("软件测试原文"));assertTrue(chunks.get(1).content().contains("测试目标\t验证需求"));
        assertTrue(chunks.get(1).content().contains("组合形状中的原文"));assertEquals(2,chunks.get(1).index());
    }
    @Test void formatMimeEncryptedLegacyAndMalformedArchivesAreRejectedBeforeQueueing() throws Exception {
        byte[] word=CourseFixtures.docx("需求分析");
        for(var upload:List.of(file("wrong.pptx",CourseFileParser.mime("PPTX"),word),
                file("wrong.docx","application/pdf",word),file("old.doc","application/octet-stream",word),
                file("bad.docx",CourseFileParser.mime("DOCX"),"not a zip".getBytes()),
                file("encrypted.docx",CourseFileParser.mime("DOCX"),new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0})))
            assertEquals(42202,assertThrows(ApiException.class,()->parser.validate(upload)).getCode());
        assertEquals(42202,assertThrows(ApiException.class,()->CourseOfficeParser.validate(Arrays.copyOf(word,40),"DOCX")).getCode());
    }
    @Test void emptyDocumentsAndDamagedPackagesProduceExplicitFailure() throws Exception {
        for(var type:List.of("DOCX","PPTX")) {
            byte[] bytes=type.equals("DOCX")?CourseFixtures.docx(""):CourseFixtures.pptx("");
            assertEquals("EMPTY_TEXT",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse(bytes,type)).code());
        }
        byte[] broken=archive("word/document.xml","<document/>",null,null);
        assertEquals("OFFICE_PARSE_ERROR",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse(broken,"DOCX")).code());
    }
    @Test void unsafePathsMacrosXmlEntitiesAndExpandedArchivesAreRejected() throws Exception {
        for(String extra:List.of("../escape.bin","/escape.bin","word/..\\escape.bin","word/vbaProject.bin")) {
            byte[] bytes=archive("word/document.xml","<document/>",extra,new byte[1]);
            assertEquals(42202,assertThrows(ApiException.class,()->CourseOfficeParser.validate(bytes,"DOCX")).getCode());
        }
        for(String xml:List.of("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///unreadable-secret'>]><document>&x;</document>",
                "<a>".repeat(129)+"</a>".repeat(129))) {
            byte[] bytes=archive("word/document.xml",xml,null,null);
            assertEquals(42202,assertThrows(ApiException.class,()->CourseOfficeParser.validate(bytes,"DOCX")).getCode());
        }
        byte[] bomb=archive("word/document.xml","<document/>","word/media/large.bin",new byte[20*1024*1024+1]);
        assertTrue(bomb.length<1024*1024);
        assertEquals(42203,assertThrows(ApiException.class,()->CourseOfficeParser.validate(bomb,"DOCX")).getCode());
    }
}

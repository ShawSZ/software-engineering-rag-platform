package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CourseFileParserTest {
    final CourseFileParser parser=new CourseFileParser();
    MockMultipartFile file(String name,String type,byte[] bytes) {return new MockMultipartFile("file",name,type,bytes);}
    @Test void strictFileFormatMimeEncodingAndSafeFilename() throws Exception {
        byte[] text="课程用例分析".getBytes(StandardCharsets.UTF_8);
        assertEquals("safe.TXT",parser.validate(file("../../outside\\safe.TXT","text/plain; charset=UTF-8",text)).filename());
        assertEquals("TXT",parser.validate(file("text.txt","application/octet-stream",text)).type());
        assertEquals(42205,assertThrows(ApiException.class,()->parser.validate(file("empty.txt","text/plain",new byte[0]))).getCode());
        assertEquals(42204,assertThrows(ApiException.class,()->parser.validate(file("bad.txt","text/plain",new byte[]{(byte)0xc3,0x28}))).getCode());
        for(var invalid:List.of(file("x.md","text/plain",text),file("x.pdf","application/pdf",text),
                file("x.txt","text/html",text),file("x.txt","text/plain",new byte[]{1,0,2}),
                file("x.txt","text/plain",CourseFixtures.pdf(false,"PDF text")))) {
            assertEquals(42202,assertThrows(ApiException.class,()->parser.validate(invalid)).getCode());
        }
        assertThrows(ApiException.class,()->parser.validate(file("bad\r\n.txt","text/plain",text)));
    }
    @Test void exactTwentyMebibyteLimit() throws Exception {
        byte[] limit=new byte[(int)CourseFileParser.MAX_BYTES];Arrays.fill(limit,(byte)'a');
        assertEquals("TXT",parser.validate(file("limit.txt","text/plain",limit)).type());
        var oversized=file("limit.txt","text/plain",Arrays.copyOf(limit,limit.length+1));
        assertEquals(42203,assertThrows(ApiException.class,()->parser.validate(oversized)).getCode());
    }
    @Test void textChunksUseUnicodePointsAndExactOriginalOverlap() {
        String source="😀课程".repeat(700);
        var chunks=parser.parse(("\uFEFF"+source).getBytes(StandardCharsets.UTF_8),"TXT");
        assertEquals(3,chunks.size());
        for(int i=0;i<chunks.size();i++) {
            int start=i*700,end=Math.min(start+800,2100);
            assertEquals(source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)),chunks.get(i).content());
            assertNull(chunks.get(i).pageNo());assertEquals(i+1,chunks.get(i).index());
        }
        String first=chunks.getFirst().content(),second=chunks.get(1).content();
        assertEquals(first.substring(first.offsetByCodePoints(0,700)),second.substring(0,second.offsetByCodePoints(0,100)));
    }
    @Test void pdfPagesRetainOneBasedLocationAndNeverMergeAcrossPages() throws Exception {
        var chunks=parser.parse(CourseFixtures.pdf(false,"First page requirements","","Third page testing"),"PDF");
        assertEquals(2,chunks.size());assertEquals(1,chunks.get(0).pageNo());assertEquals(3,chunks.get(1).pageNo());
        assertTrue(chunks.get(0).content().contains("First page requirements"));
        assertFalse(chunks.get(0).content().contains("Third"));assertEquals(2,chunks.get(1).index());
    }
    @Test void emptyScannedCorruptAndEncryptedFilesFailWithoutInventedText() throws Exception {
        assertEquals("EMPTY_TEXT",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse(" \r\n\t".getBytes(),"TXT")).code());
        assertEquals("EMPTY_TEXT",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse(CourseFixtures.scannedPdf(),"PDF")).code());
        assertEquals("PDF_PARSE_ERROR",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse("%PDF-1.7 corrupt".getBytes(),"PDF")).code());
        assertEquals("PDF_PARSE_ERROR",assertThrows(CourseFileParser.ParseFailure.class,()->parser.parse(CourseFixtures.pdf(true,"protected"),"PDF")).code());
    }
}

package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Local OOXML extraction only: no macros, external resources, embedded objects or OCR. */
final class CourseOfficeParser {
    record Text(String content,Integer pageNo) { }
    private CourseOfficeParser() { }
    static void validate(byte[] data,String type) {
        if(data.length<4 || data[0]!='P' || data[1]!='K' || data[2]!=3 || data[3]!=4) throw invalid();
        String required=type.equals("DOCX")?"word/document.xml":"ppt/presentation.xml";
        boolean main=false,contentTypes=false;long total=0;int count=0;var names=new HashSet<String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(++count>2048 || !names.add(name) || name.startsWith("/") || name.contains("..") || name.contains("\\")
                        || name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw invalid();
                main|=name.equals(required);contentTypes|=name.equals("[Content_Types].xml");
                long size=0;int read;
                boolean xml=name.endsWith(".xml") || name.endsWith(".rels");
                var xmlBytes=xml?new ByteArrayOutputStream():null;
                while((read=zip.read(buffer))!=-1) {
                    total+=read;size+=read;
                    if(total>64L*1024*1024 || size>20L*1024*1024) throw new ApiException(42203,"Office文件展开后过大，请拆分资料后上传");
                    if(xml) xmlBytes.write(buffer,0,read);
                }
                if(xml) checkXml(xmlBytes.toByteArray());
                zip.closeEntry();
            }
        }catch(ApiException failure){throw failure;}catch(IOException failure){throw invalid();}
        if(!main || !contentTypes || (type.equals("DOCX") && names.contains("ppt/presentation.xml"))
                || (type.equals("PPTX") && names.contains("word/document.xml"))) throw invalid();
    }
    private static ApiException invalid(){return new ApiException(42202,"Office内容与扩展名不匹配或文件结构损坏");}
    private static void checkXml(byte[] data) {
        try {
            var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            factory.setXMLResolver((publicId,systemId,baseUri,namespace)->{throw new XMLStreamException("External resource forbidden");});
            var xml=factory.createXMLStreamReader(new ByteArrayInputStream(data));int depth=0;
            try {while(xml.hasNext()) {
                int event=xml.next();
                if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE) throw invalid();
                if(event==XMLStreamConstants.START_ELEMENT && ++depth>128) throw invalid();
                if(event==XMLStreamConstants.END_ELEMENT) depth--;
            }}finally{xml.close();}
        }catch(ApiException failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    static List<Text> read(byte[] bytes,String type) {
        try {
            validate(bytes,type);
            if(type.equals("DOCX")) try(var document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
                var text=new StringBuilder();word(document.getBodyElements(),text,0);return List.of(new Text(text.toString(),null));
            }
            try(var presentation=new XMLSlideShow(new ByteArrayInputStream(bytes))) {
                var result=new ArrayList<Text>();int page=0;
                for(var slide:presentation.getSlides()) {var text=new StringBuilder();slides(slide.getShapes(),text,0);result.add(new Text(text.toString(),++page));}
                return result;
            }
        }catch(Exception failure){throw new CourseFileParser.ParseFailure("OFFICE_PARSE_ERROR","Office文件损坏、加密或无法提取文字，请另存为可读的DOCX/PPTX后重新上传");}
    }
    private static void word(List<IBodyElement> elements,StringBuilder text,int depth) {
        if(depth>32) throw new IllegalArgumentException("Nested document limit");
        for(var element:elements) {
            if(element instanceof XWPFParagraph paragraph) text.append(paragraph.getText()).append('\n');
            else if(element instanceof XWPFTable table) for(var row:table.getRows()) {
                for(var cell:row.getTableCells()) {word(cell.getBodyElements(),text,depth+1);text.append('\t');}text.append('\n');
            }
        }
    }
    private static void slides(List<XSLFShape> shapes,StringBuilder text,int depth) {
        if(depth>32) throw new IllegalArgumentException("Nested presentation limit");
        for(var shape:shapes) {
            if(shape instanceof XSLFGroupShape group) slides(group.getShapes(),text,depth+1);
            else if(shape instanceof XSLFTable table) for(var row:table.getRows()) {
                for(var cell:row.getCells()) text.append(cell.getText()).append('\t');text.append('\n');
            }
            else if(shape instanceof XSLFTextShape paragraph) text.append(paragraph.getText()).append('\n');
        }
    }
}

package com.fast.knowledge.course;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import java.io.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

/** Self-authored synthetic course text; no third-party or private documents. */
public final class CourseFixtures {
    private CourseFixtures() { }
    public static byte[] docx(String text) throws IOException {
        try(var document=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            if(!text.isBlank()) {
                var table=document.createTable(2,2);
                table.getRow(0).getCell(0).setText("测试类型");table.getRow(0).getCell(1).setText("检查内容");
                table.getRow(1).getCell(0).setText("单元测试");table.getRow(1).getCell(1).setText("检查独立模块的行为");
            }
            document.write(out);return out.toByteArray();
        }
    }
    public static byte[] pptx(String... pages) throws IOException {
        try(var document=new XMLSlideShow();var out=new ByteArrayOutputStream()) {
            for(String text:pages) {
                var slide=document.createSlide();slide.createTextBox().setText(text);
                if(!text.isBlank()) {
                    var table=slide.createTable(1,2);table.getCell(0,0).setText("测试目标");table.getCell(0,1).setText("验证需求");
                    slide.createGroup().createTextBox().setText("组合形状中的原文");
                }
            }
            document.write(out);return out.toByteArray();
        }
    }
    public static byte[] scannedPdf() throws IOException {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();document.addPage(page);
            var image=new java.awt.image.BufferedImage(500,100,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,500,100);
            graphics.setColor(java.awt.Color.BLACK);graphics.drawString("Synthetic scanned requirements",20,50);graphics.dispose();
            try(var content=new PDPageContentStream(document,page)) {
                content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,image),40,600);
            }
            document.save(out);return out.toByteArray();
        }
    }
    public static byte[] pdf(boolean encrypted,String... pages) throws IOException {
        try(var document=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(String text:pages) {
                var page=new PDPage();document.addPage(page);
                if(!text.isEmpty()) try(var content=new PDPageContentStream(document,page)) {
                    content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    content.newLineAtOffset(40,700);content.showText(text);content.endText();
                }
            }
            if(encrypted) {
                var permission=new AccessPermission();permission.setCanExtractContent(false);
                document.protect(new StandardProtectionPolicy("owner-fixture","reader-fixture",permission));
            }
            document.save(out);return out.toByteArray();
        }
    }
}

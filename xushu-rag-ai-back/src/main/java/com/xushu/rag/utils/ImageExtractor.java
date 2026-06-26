package com.xushu.rag.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF图片提取工具
 * <p>
 * 从PDF文档中提取内嵌图片，保存为临时PNG文件供多模态模型分析。
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class ImageExtractor {

    /**
     * 从PDF中提取所有内嵌图片
     *
     * @param pdfPath PDF文件路径
     * @return 提取的图片信息列表（包含图片路径和页码）
     * @throws IOException 文件读取异常
     */
    public List<ExtractedImage> extractImages(String pdfPath) throws IOException {
        List<ExtractedImage> images = new ArrayList<>();
        File pdfFile = new File(pdfPath);

        if (!pdfFile.exists()) {
            log.warn("PDF文件不存在: {}", pdfPath);
            return images;
        }

        try (PDDocument document = PDDocument.load(pdfFile)) {
            for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
                PDPage page = document.getPage(pageNum);
                PDResources resources = page.getResources();
                int imageIndex = 0;
                for (COSName name : resources.getXObjectNames()) {
                    if (!resources.isImageXObject(name)) {
                        continue;
                    }
                    PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                    try {
                        BufferedImage bufferedImage = image.getImage();
                        if (bufferedImage == null) {
                            continue;
                        }

                        // 保存为临时PNG
                        String tempDir = System.getProperty("java.io.tmpdir");
                        String imageName = pdfFile.getName().replace(".pdf", "")
                                + "_page" + (pageNum + 1)
                                + "_img" + imageIndex + ".png";
                        Path imagePath = Paths.get(tempDir, imageName);
                        ImageIO.write(bufferedImage, "PNG", imagePath.toFile());

                        // 跳过过小的图片（可能是装饰元素）
                        long fileSize = Files.size(imagePath);
                        if (fileSize < 2048) { // 小于2KB跳过
                            Files.deleteIfExists(imagePath);
                            imageIndex++;
                            continue;
                        }

                        ExtractedImage extImage = new ExtractedImage(
                                imagePath.toAbsolutePath().toString(),
                                imageName,
                                pageNum + 1,
                                bufferedImage.getWidth(),
                                bufferedImage.getHeight(),
                                fileSize
                        );
                        images.add(extImage);
                        log.debug("提取图片: page={}, image={}, size={}x{}",
                                pageNum + 1, imageIndex,
                                bufferedImage.getWidth(), bufferedImage.getHeight());

                    } catch (Exception e) {
                        log.warn("提取图片失败: page={}, imageIndex={}, error={}",
                                pageNum + 1, imageIndex, e.getMessage());
                    }
                    imageIndex++;
                }
            }
        }

        log.info("PDF图片提取完成: {} → {}张图片", pdfFile.getName(), images.size());
        return images;
    }

    /**
     * 从DOCX中提取所有内嵌图片
     *
     * @param docxPath DOCX文件路径
     * @return 提取的图片信息列表
     * @author Joseph
     */
    public List<ExtractedImage> extractImagesFromDocx(String docxPath) throws IOException {
        List<ExtractedImage> images = new ArrayList<>();
        File docxFile = new File(docxPath);

        if (!docxFile.exists()) {
            log.warn("DOCX文件不存在: {}", docxPath);
            return images;
        }

        try (FileInputStream fis = new FileInputStream(docxFile);
             XWPFDocument document = new XWPFDocument(fis)) {

            List<XWPFPictureData> pictures = document.getAllPictures();
            for (int i = 0; i < pictures.size(); i++) {
                XWPFPictureData pic = pictures.get(i);
                try {
                    byte[] data = pic.getData();
                    if (data == null || data.length < 2048) {
                        continue;
                    }

                    BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(data));
                    if (bufferedImage == null) {
                        log.debug("无法解码DOCX图片: index={}", i);
                        continue;
                    }

                    String tempDir = System.getProperty("java.io.tmpdir");
                    String imageName = docxFile.getName().replace(".docx", "")
                            + "_img" + i + ".png";
                    Path imagePath = Paths.get(tempDir, imageName);
                    ImageIO.write(bufferedImage, "PNG", imagePath.toFile());

                    ExtractedImage extImage = new ExtractedImage(
                            imagePath.toAbsolutePath().toString(),
                            imageName,
                            1,  // DOCX不分页，统一page=1
                            bufferedImage.getWidth(),
                            bufferedImage.getHeight(),
                            data.length
                    );
                    images.add(extImage);
                    log.debug("提取DOCX图片: index={}, size={}x{}",
                            i, bufferedImage.getWidth(), bufferedImage.getHeight());

                } catch (Exception e) {
                    log.warn("提取DOCX图片失败: index={}, error={}", i, e.getMessage());
                }
            }
        }

        log.info("DOCX图片提取完成: {} → {}张图片", docxFile.getName(), images.size());
        return images;
    }

    /**
     * 提取的图片信息
     */
    public static class ExtractedImage {
        private final String path;
        private final String name;
        private final int pageNumber;
        private final int width;
        private final int height;
        private final long fileSize;

        public ExtractedImage(String path, String name, int pageNumber,
                              int width, int height, long fileSize) {
            this.path = path;
            this.name = name;
            this.pageNumber = pageNumber;
            this.width = width;
            this.height = height;
            this.fileSize = fileSize;
        }

        public String getPath() { return path; }
        public String getName() { return name; }
        public int getPageNumber() { return pageNumber; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public long getFileSize() { return fileSize; }
    }
}

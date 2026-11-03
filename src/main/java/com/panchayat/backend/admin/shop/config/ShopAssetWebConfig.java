package com.panchayat.backend.admin.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Files in the upload folder, served at /uploads/**.
 *
 * Admin media (/uploads/media/**: gift pictures, SVGA and MP4 animations, MP3
 * sounds, reward effects) never changes under the same name, so phones keep it
 * for a year and load it from their cache next time, with no new download.
 */
@Configuration
public class ShopAssetWebConfig implements WebMvcConfigurer {

    @Value("${app.upload.directory:uploads}")
    private String uploadDirectory;

    @Override
    public void addResourceHandlers(
            ResourceHandlerRegistry registry
    ) {
        Path uploadPath = Paths.get(uploadDirectory)
                .toAbsolutePath()
                .normalize();

        registry.addResourceHandler("/uploads/media/**")
                .addResourceLocations(uploadPath.resolve("media").toUri().toString())
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(
                        uploadPath.toUri().toString()
                );
    }
}

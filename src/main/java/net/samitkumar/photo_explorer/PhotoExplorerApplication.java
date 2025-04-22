package net.samitkumar.photo_explorer;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.name.Rename;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootApplication
@Slf4j
public class PhotoExplorerApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhotoExplorerApplication.class, args);
	}

	@Value("${spring.application.file.upload.path}")
	private String fileUploadPath;

	@Bean
	RouterFunction<ServerResponse> routerFunction() {
		return RouterFunctions
				.route()
				.POST("/upload", this::upload)
				.GET("/explorer", this::fileExplorer)
				.GET("/view/{fileName}", this::viewFile)
				.build();
	}

	private Mono<ServerResponse> viewFile(ServerRequest request) {
		return ServerResponse.noContent().build();
	}

	@SneakyThrows
	private Mono<ServerResponse> fileExplorer(ServerRequest request) {
		//list all the files in the directory
		return Mono.fromCallable(() -> Files.walk(Paths.get(fileUploadPath))
				.filter(Files::isRegularFile)
				.filter(file -> !file.getFileName().toString().contains(".thumbnail"))
				.map(Path::toFile)
				.map(FileExplorerResponse::fromFile)
				.collect(Collectors.toList()))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(ServerResponse.ok()::bodyValue);
	}

	private Mono<ServerResponse> upload(ServerRequest request) {
		return request
				.multipartData()
				.map(MultiValueMap::toSingleValueMap)
				.map(stringPartMap -> stringPartMap.get("file"))
				.cast(FilePart.class)
				//validate and accept only file with content type image/*
				//.filter(filePart -> filePart.headers().getContentType().toString().startsWith("image/"))
				.doOnNext(filePart -> {
					log.info("Received file: {}", filePart.filename());
					log.info("File size: {} bytes", filePart.headers().getContentLength());
					log.info("File type: {}", filePart.headers().getContentType());
					log.info("File disposition: {}", filePart.headers().getContentDisposition());
				})
				.flatMap(this::fileUploadToDisk)
				.flatMap(this::createThumbnail)
				.then(ServerResponse.ok().bodyValue(Map.of("status", "SUCCESS")));
	}

	@SneakyThrows
	private Mono<Void> createThumbnail(FilePart filePart) {
		return Mono.fromCallable(() -> {
					var originalFile = Path.of(fileUploadPath).resolve(filePart.filename()).toFile();
					if (!originalFile.exists()) {
						log.error("Original file does not exist: {}", originalFile);
						throw new IOException("Original file does not exist");
					}
					//TODO find a better way to get the file extension
					String fileExtension = originalFile.getName().substring(originalFile.getName().lastIndexOf(".") + 1);
					log.info("Creating thumbnail for file: {} on fileUploadPath {}", filePart.filename(), originalFile);
					Thumbnails.of(originalFile)
							.size(100, 100)
							.outputFormat(fileExtension)
							.toFiles(Rename.SUFFIX_DOT_THUMBNAIL);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic()) // Offload to a bounded elastic thread pool
				.then();
	}


	private Mono<FilePart> fileUploadToDisk(FilePart filePart) {
		var transferTo = Path.of(fileUploadPath).resolve(filePart.filename());
		log.info("Uploading file to disk: {}", transferTo);
		return filePart
				.transferTo(transferTo)
				.doOnError(ex -> log.error(ex.getMessage()))
				.thenReturn(filePart);
	}
}

record FileExplorerResponse(String fileName, String fileExtensions, String thumbnailFileName, Long fileSize) {
	public static FileExplorerResponse fromFile(File file) {
		return new FileExplorerResponse(
				file.getName(),
				file.getName().substring(file.getName().lastIndexOf(".") + 1),
				"<fileName>.thumbnail.<fileExtension>",
				file.length()
		);
	}
}
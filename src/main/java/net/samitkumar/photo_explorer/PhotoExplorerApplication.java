package net.samitkumar.photo_explorer;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.name.Rename;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.MediaType.*;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

@SpringBootApplication
@Slf4j
public class PhotoExplorerApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhotoExplorerApplication.class, args);
	}

	@Value("${spring.application.file.upload.path}")
	private String fileUploadBasePath;

	//cors configuration
	@Bean
	CorsWebFilter corsFilter() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowCredentials(true);
		config.addAllowedOriginPattern("*");
		config.addAllowedHeader("*");
		config.addAllowedMethod("*");
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return new CorsWebFilter(source);
	}

	@Bean
	RouterFunction<ServerResponse> routerFunction() {
		return RouterFunctions
				.route()
				.path("/file", builder -> builder
						.POST("/upload", accept(MULTIPART_FORM_DATA), this::uploadFile)
						.GET("/explorer", this::fileExplorer)
						.GET("/view/{fileName}", this::viewFile)
						.GET("/download/{fileName}", this::downloadFile))
				.build();
	}

	private Mono<ServerResponse> downloadFile(ServerRequest request) {
		var fileName = request.pathVariable("fileName");
		var filePath = Path.of(fileUploadBasePath).resolve(fileName);
		log.info("Downloading file: {}", filePath);
		if (!Files.exists(filePath)) {
			log.error("File not found to be download: {}", filePath);
			return ServerResponse.notFound().build();
		}

		var byteArrayMono = getFileAsByteArray(filePath);
		return ServerResponse.ok()
				.contentType(APPLICATION_OCTET_STREAM)
				.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
				.body(byteArrayMono, byte[].class);
	}

	private Mono<ServerResponse> viewFile(ServerRequest request) {
		var fileName = request.pathVariable("fileName");
		var filePath = Path.of(fileUploadBasePath).resolve(fileName);
		log.info("Viewing file: {}", filePath);
		if (!Files.exists(filePath)) {
			log.error("File not found for view: {}", filePath);
			return ServerResponse.notFound().build();
		}
		//find out file type
		var fileType = fileName.substring(fileName.lastIndexOf(".") + 1);
		log.info("File type to be view: {}", fileType);
		MediaType mediaType;
		switch (fileType) {
			case "jpg", "jpeg" -> mediaType = IMAGE_JPEG;
			case "png" -> mediaType = IMAGE_PNG;
			case "gif" -> mediaType = IMAGE_GIF;
			case "webp" -> mediaType = MediaType.parseMediaType("image/webp");
			default -> mediaType = APPLICATION_OCTET_STREAM;
		}

		var byteArrayMono = getFileAsByteArray(filePath);

		return ServerResponse.ok()
				.contentType(mediaType)
				.header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
				.body(byteArrayMono, byte[].class);
	}

	private Mono<byte[]> getFileAsByteArray(Path filePath) {
		return Mono.fromCallable(() -> Files.readAllBytes(filePath))
				.subscribeOn(Schedulers.boundedElastic());
	}

	@SneakyThrows
	private Mono<ServerResponse> fileExplorer(ServerRequest request) {
		//list all the files in the directory
		return Mono.fromCallable(
					() -> Files.walk(Paths.get(fileUploadBasePath), 10)
					.filter(Files::isRegularFile)
					.filter(file -> !file.getFileName().toString().contains(".thumbnail"))
					.map(Path::toFile)
					.map(FileExplorerResponse::fromFile)
					.collect(Collectors.toList())
				)
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(ServerResponse.ok()::bodyValue);
	}

	private Mono<ServerResponse> uploadFile(ServerRequest request) {
		return request
				.multipartData()
				.map(MultiValueMap::toSingleValueMap)
				.map(stringPartMap -> stringPartMap.get("file"))
				.cast(FilePart.class)
				//validate and accept only file with content type image/*
				.doOnNext(filePart -> {
					log.info("Received file: {}", filePart.filename());
					log.info("File size: {} bytes", filePart.headers().getContentLength());
					log.info("File type: {}", filePart.headers().getContentType());
					log.info("File disposition: {}", filePart.headers().getContentDisposition());
				})
				//.filter(filePart -> filePart.headers().getContentType().toString().startsWith("image/"))
				//.doOnNext(this::validate)
				.flatMap(this::validate)
				.switchIfEmpty(Mono.error(new NotSupportedFileTypeException("File type not supported")))
				.flatMap(this::fileUploadToDisk)
				.flatMap(this::createThumbnail)
				.then(ServerResponse.ok().bodyValue(Map.of("status", "SUCCESS")));
	}

	private Mono<FilePart> validate(FilePart filePart) {
		var fileExtension = filePart.filename().substring(filePart.filename().lastIndexOf(".") + 1);
		var supportedFileTypes = Stream.of("jpg", "jpeg", "png", "gif", "webp")
				.collect(Collectors.toSet());
		if(!filePart.headers().getContentType().toString().startsWith("image/")) {
			log.error("File type not supported: {}", fileExtension);
			return Mono.empty();
		} else if (filePart.headers().getContentType().toString().startsWith("video/")) {
			log.error("File type not supported: {}", fileExtension);
			return Mono.empty();
		} else if (!supportedFileTypes.contains(fileExtension)) {
			log.error("File type not supported: {}", fileExtension);
			return Mono.empty();
		}
		return Mono.just(filePart);
	}

	@SneakyThrows
	private Mono<Void> createThumbnail(FilePart filePart) {
		return Mono.fromCallable(() -> {
					var originalFile = Path.of(fileUploadBasePath).resolve(filePart.filename()).toFile();
					if (!originalFile.exists()) {
						log.error("Original file does not exist: {}", originalFile);
						throw new IOException("Original file does not exist");
					}
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
		var transferTo = Path.of(fileUploadBasePath).resolve(filePart.filename());
		log.info("Uploading file to disk: {}", transferTo);
		return filePart
				.transferTo(transferTo)
				.doOnError(ex -> log.error(ex.getMessage()))
				.thenReturn(filePart);
	}
}

record FileExplorerResponse(String fileName, String fileExtensions, String thumbnailFileName, Long fileSize, String imageHeight, String imageWidth) {
	public static FileExplorerResponse fromFile(File file) {
		var fileName = file.getName();
		var fileNameWithoutExtension = fileName.substring(0, fileName.lastIndexOf("."));
		var fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
		return new FileExplorerResponse(
				fileName,
				fileExtension,
				"%s.thumbnail.%s".formatted(fileNameWithoutExtension, fileExtension),
				file.length(),
				"0",
				"0"
		);
	}
}

@ResponseStatus(HttpStatus.BAD_REQUEST)
class NotSupportedFileTypeException extends RuntimeException {
	public NotSupportedFileTypeException(String message) {
		super(message);
	}
}
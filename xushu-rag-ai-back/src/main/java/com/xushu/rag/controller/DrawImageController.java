/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xushu.rag.controller;

import com.xushu.rag.annotation.Loggable;
import com.xushu.rag.common.ApplicationConstant;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * @author xushu
 */

@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/draw")
public class DrawImageController {

	private final ImageModel imageModel;

	public DrawImageController(ImageModel imageModel) {
		this.imageModel = imageModel;
	}

	@GetMapping("/image")
	@Loggable
	public void image(HttpServletResponse response, @RequestParam(value = "prompt") String prompt) {
		ImageResponse imageResponse = imageModel.call(new ImagePrompt(prompt));
		String imageUrl = imageResponse.getResult().getOutput().getUrl();

		try {
			URL url = URI.create(imageUrl).toURL();
			InputStream in = url.openStream();

			response.setHeader("Content-Type", MediaType.IMAGE_PNG_VALUE);
			response.getOutputStream().write(in.readAllBytes());
			response.getOutputStream().flush();
		} catch (IOException e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

}

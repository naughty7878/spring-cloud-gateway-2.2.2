/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.handler.predicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.style.ToStringCreator;
import org.springframework.http.server.PathContainer;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPattern.PathMatchInfo;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.putUriTemplateVariables;
import static org.springframework.http.server.PathContainer.parsePath;

/**
 * @author Spencer Gibb
 */
public class PathRoutePredicateFactory
		extends AbstractRoutePredicateFactory<PathRoutePredicateFactory.Config> {

	private static final Log log = LogFactory.getLog(RoutePredicateFactory.class);

	private static final String MATCH_OPTIONAL_TRAILING_SEPARATOR_KEY = "matchOptionalTrailingSeparator";

	private PathPatternParser pathPatternParser = new PathPatternParser();

	public PathRoutePredicateFactory() {
		super(Config.class);
	}

	private static void traceMatch(String prefix, Object desired, Object actual,
			boolean match) {
		if (log.isTraceEnabled()) {
			String message = String.format("%s \"%s\" %s against value \"%s\"", prefix,
					desired, match ? "matches" : "does not match", actual);
			log.trace(message);
		}
	}

	public void setPathPatternParser(PathPatternParser pathPatternParser) {
		this.pathPatternParser = pathPatternParser;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList("patterns", MATCH_OPTIONAL_TRAILING_SEPARATOR_KEY);
	}

	@Override
	public ShortcutType shortcutType() {
		return ShortcutType.GATHER_LIST_TAIL_FLAG;
	}

	@Override
	public Predicate<ServerWebExchange> apply(Config config) {
		final ArrayList<PathPattern> pathPatterns = new ArrayList<>();
		synchronized (this.pathPatternParser) {
			pathPatternParser.setMatchOptionalTrailingSeparator(
					config.isMatchOptionalTrailingSeparator());
			// 从配置中获取模版
			// 遍历
			config.getPatterns().forEach(pattern -> {
				// 解析模版
				PathPattern pathPattern = this.pathPatternParser.parse(pattern);
				// 将路径模版添加到集合中
				pathPatterns.add(pathPattern);
			});
		}
		return new GatewayPredicate() {
			@Override
			public boolean test(ServerWebExchange exchange) {
				// 从契约中获取路径
				PathContainer path = parsePath(
						exchange.getRequest().getURI().getRawPath());
				// 使用路径模版，过滤路径
				Optional<PathPattern> optionalPathPattern = pathPatterns.stream()
						.filter(pattern -> pattern.matches(path)).findFirst();
				// 判断路径是否过滤后还存在
				if (optionalPathPattern.isPresent()) {
					PathPattern pathPattern = optionalPathPattern.get();
					traceMatch("Pattern", pathPattern.getPatternString(), path, true);
					PathMatchInfo pathMatchInfo = pathPattern.matchAndExtract(path);
					putUriTemplateVariables(exchange, pathMatchInfo.getUriVariables());
					return true;
				}
				else {
					traceMatch("Pattern", config.getPatterns(), path, false);
					return false;
				}
			}

			@Override
			public String toString() {
				return String.format("Paths: %s, match trailing slash: %b",
						config.getPatterns(), config.isMatchOptionalTrailingSeparator());
			}
		};
	}

	@Validated
	public static class Config {

		private List<String> patterns = new ArrayList<>();

		private boolean matchOptionalTrailingSeparator = true;

		@Deprecated
		public String getPattern() {
			if (!CollectionUtils.isEmpty(this.patterns)) {
				return patterns.get(0);
			}
			return null;
		}

		@Deprecated
		public Config setPattern(String pattern) {
			this.patterns = new ArrayList<>();
			this.patterns.add(pattern);
			return this;
		}

		public List<String> getPatterns() {
			return patterns;
		}

		public Config setPatterns(List<String> patterns) {
			this.patterns = patterns;
			return this;
		}

		public boolean isMatchOptionalTrailingSeparator() {
			return matchOptionalTrailingSeparator;
		}

		public Config setMatchOptionalTrailingSeparator(
				boolean matchOptionalTrailingSeparator) {
			this.matchOptionalTrailingSeparator = matchOptionalTrailingSeparator;
			return this;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("patterns", patterns)
					.append("matchOptionalTrailingSeparator",
							matchOptionalTrailingSeparator)
					.toString();
		}

	}

}

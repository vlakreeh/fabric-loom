/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MinecraftAssetsProvider;
import net.fabricmc.loom.providers.MinecraftProvider;

public class RunConfig {
	public String configName;
	public String projectName;
	public String mainClass;
	public String runDir;
	public String vmArgs;
	public String programArgs;

	public Element genRuns(Element doc) throws IOException, ParserConfigurationException, TransformerException {
		Element root = this.addXml(doc, "component", ImmutableMap.of("name", "ProjectRunConfigurationManager"));
		root = addXml(root, "configuration", ImmutableMap.of("default", "false", "name", configName, "type", "Application", "factoryName", "Application"));

		this.addXml(root, "module", ImmutableMap.of("name", projectName));
		this.addXml(root, "option", ImmutableMap.of("name", "MAIN_CLASS_NAME", "value", mainClass));
		this.addXml(root, "option", ImmutableMap.of("name", "WORKING_DIRECTORY", "value", runDir));

		if (!Strings.isNullOrEmpty(vmArgs)) {
			this.addXml(root, "option", ImmutableMap.of("name", "VM_PARAMETERS", "value", vmArgs));
		}

		if (!Strings.isNullOrEmpty(programArgs)) {
			this.addXml(root, "option", ImmutableMap.of("name", "PROGRAM_PARAMETERS", "value", programArgs));
		}
		return root;
	}

	public Element addXml(Node parent, String name, Map<String, String> values) {
		Document doc = parent.getOwnerDocument();
		if (doc == null) {
			doc = (Document) parent;
		}

		Element e = doc.createElement(name);
		for (Map.Entry<String, String> entry : values.entrySet()) {
			e.setAttribute(entry.getKey(), entry.getValue());
		}
		parent.appendChild(e);
		return e;
	}

	private static void populate(Project project, LoomGradleExtension extension, RunConfig runConfig, String mode) {
		runConfig.projectName = project.getName();
		runConfig.runDir = "file://$PROJECT_DIR$/" + extension.runDir;
		runConfig.vmArgs = "-Dfabric.development=true";

		runConfig.mainClass = "net.minecraft.launchwrapper.Launch";
		runConfig.programArgs = "";

		// if installer.json found...
		JsonObject installerJson = extension.getInstallerJson();
		if (installerJson != null) {
			List<String> sideKeys = ImmutableList.of(mode, "common");

			// copy main class
			if (installerJson.has("mainClass")) {
				JsonElement mainClassJson = installerJson.get("mainClass");
				if (mainClassJson.isJsonObject()) {
					JsonObject mainClassesJson = mainClassJson.getAsJsonObject();
					for (String s : sideKeys) {
						if (mainClassesJson.has(s)) {
							runConfig.mainClass = mainClassesJson.get(s).getAsString();
							break;
						}
					}
				} else {
					runConfig.mainClass = mainClassJson.getAsString();
				}
			}

			// copy launchwrapper tweakers
			if (installerJson.has("launchwrapper")) {
				JsonObject launchwrapperJson = installerJson.getAsJsonObject("launchwrapper");
				if (launchwrapperJson.has("tweakers")) {
					JsonObject tweakersJson = launchwrapperJson.getAsJsonObject("tweakers");
					StringBuilder builder = new StringBuilder();
					for (String s : sideKeys) {
						if (tweakersJson.has(s)) {
							for (JsonElement element : tweakersJson.getAsJsonArray(s)) {
								builder.append(" --tweakClass ").append(element.getAsString());
							}
						}
					}
					runConfig.programArgs += builder.toString();
				}
			}
		}
	}

	public static RunConfig clientRunConfig(Project project){
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MinecraftProvider minecraftProvider =  extension.getMinecraftProvider();
		MinecraftVersionInfo minecraftVersionInfo = minecraftProvider.versionInfo;

		if (extension.tweakClass.isEmpty()) {
			project.getLogger().warn("No tweakClass provided, using a placeholder.");
			extension.tweakClass = "PlacejolderTweaker";
		}

		RunConfig ideaClient = new RunConfig();
		populate(project, extension, ideaClient, "client");
		ideaClient.configName = "Minecraft Client";
		ideaClient.programArgs += " --assetIndex \"" + minecraftVersionInfo.assetIndex.getFabricId(extension.getMinecraftProvider().minecraftVersion) + "\" --tweakClass " + extension.tweakClass + " --accessToken \"\" --version " + extension.getMinecraftProvider().minecraftVersion;
		ideaClient.vmArgs += getOSClientJVMArgs();

		return ideaClient;
	}

	public static RunConfig serverRunConfig(Project project){
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

		RunConfig ideaServer = new RunConfig();
		populate(project, extension, ideaServer, "server");
		ideaServer.configName = "Minecraft Server";

		return ideaServer;
	}

	public String fromDummy(String dummy) throws IOException {
		String dummyConfig;
		try (InputStream input = SetupIntelijRunConfigs.class.getClassLoader().getResourceAsStream(dummy)) {
			dummyConfig = IOUtils.toString(input, StandardCharsets.UTF_8);
		}

		dummyConfig = dummyConfig.replace("%NAME%", configName);
		dummyConfig = dummyConfig.replace("%MAIN_CLASS%", mainClass);
		dummyConfig = dummyConfig.replace("%MODULE%", projectName);
		dummyConfig = dummyConfig.replace("%PROGRAM_ARGS%", programArgs.replaceAll("\"", "&quot;"));
		dummyConfig = dummyConfig.replace("%VM_ARGS%", vmArgs.replaceAll("\"", "&quot;"));

		return dummyConfig;
	}

	public static String getOSClientJVMArgs(){
		if(OperatingSystem.getOS().equalsIgnoreCase("osx")){
			return " -XstartOnFirstThread";
		}
		return "";
	}
}
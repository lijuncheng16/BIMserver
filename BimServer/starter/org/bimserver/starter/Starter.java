package org.bimserver.starter;

/******************************************************************************
 * (c) Copyright bimserver.org 2009
 * Licensed under GNU GPLv3
 * http://www.gnu.org/licenses/gpl-3.0.txt
 * For more information mail to license@bimserver.org
 *
 * Bimserver.org is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License as published by 
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bimserver.org is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License a 
 * long with Bimserver.org . If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/


import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;



public class Starter {
	private Process exec;
	private JarSettings jarSettings = JarSettings.readFromFile();
	private String addressField;
	private String portField;
	private String heapSizeField;
	private String permSizeField;
	private boolean forceIpv4Field;
	private String stackSizeField;
	private String jvmField;
	private String homeDirField;

	public static void main(String[] args) {
		new Starter().start();
	}

//	public void save() {
//		try {
//			jarSettings.setAddress(addressField);
//			jarSettings.setPort(Integer.parseInt(portField));
//			jarSettings.setJvm(jvmField);
//			jarSettings.setStacksize(stackSizeField);
//			jarSettings.setHeapsize(heapSizeField);
//			jarSettings.setPermsize(permSizeField);
//			jarSettings.setHomedir(homeDirField);
//			jarSettings.setForceipv4(forceIpv4Field);
//			jarSettings.save();
//		} catch (Exception e) {
//			// ignore
//		}
//	}
	
	private void start() {
		final PrintStream systemOut = System.out;
		addressField = jarSettings.getAddress();
		portField = Integer.toString(jarSettings.getPort());
		heapSizeField = jarSettings.getHeapsize();
		permSizeField = jarSettings.getPermsize();
		forceIpv4Field = jarSettings.isForceipv4();
		stackSizeField = jarSettings.getStacksize();
		jvmField = jarSettings.getJvm();
		homeDirField = jarSettings.getHomedir();		
		
		PrintStream out = new PrintStream(new OutputStream() {
			@Override
			public void write(byte[] bytes, int off, int len) throws IOException {
				String str = new String(bytes, off, len);
				systemOut.append(str);
			}
			
			@Override
			public void write(int b) throws IOException {
				String str = new String(new char[] { (char) b });
				systemOut.append(str);
			}
		});
		System.setOut(out);
		System.setErr(out);

		new Thread(new Runnable() {
			@Override
			public void run() {
				if (jvmField.equalsIgnoreCase("default") || new File(jvmField).exists()) {
					File file = expand();
					start(file, addressField, portField, heapSizeField, stackSizeField, permSizeField, jvmField, homeDirField);
				} else {
					systemOut.append("JVM field should contain a valid JVM directory, or 'default' for the default JVM");
					}
				}
			}, "BIMserver Starter Thread").start();
				 
		
		
		
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				if (exec != null) {
					exec.destroy();
				}
			}
		}, "Thutdown Hook"));
	}

	private void start(File destDir, String address, String port, String heapsize, String stacksize, String permsize, String jvmPath, String homedir) {
		try {
			String os = System.getProperty("os.name");
			boolean isMac = os.toLowerCase().contains("mac");
			System.out.println("OS: " + os);
//			String command = "";
			
			List<String> commands = new ArrayList<>();
			
			if (jvmPath.equalsIgnoreCase("default")) {
				commands.add("java");
			} else {
				File jvm = new File(jvmPath);
				if (jvm.exists()) {
					File jre = new File(jvm, "jre");
					if (!jre.exists()) {
						jre = jvm;
					}
					commands.add(new File(jre, "bin" + File.separator + "java").getAbsolutePath());
					File jreLib = new File(jre, "lib");
					
					System.out.println("Using " + jreLib.getAbsolutePath() + " for bootclasspath");
					
					String xbcp = "-Xbootclasspath:";
					for (File file : jreLib.listFiles()) {
						if (file.getName().endsWith(".jar")) {
							if (file.getAbsolutePath().contains(" ")) {
								xbcp += "\"" + file.getAbsolutePath() + "\"" + File.pathSeparator;
							} else {
								xbcp += file.getAbsolutePath() + File.pathSeparator;
							}
						}
					}
					if (jre != jvm) {
						File toolsFile = new File(jvm, "lib" + File.separator + "tools.jar");
						if (toolsFile.getAbsolutePath().contains(" ")) {
							xbcp += "\"" + toolsFile.getAbsolutePath() + "\"";
						} else {
							xbcp += toolsFile.getAbsolutePath();
						}
					}
					commands.add(xbcp);
				} else {
					System.out.println("Not using selected JVM (directory not found), using default JVM");
				}
			}
			commands.add("-Xmx" + heapsize);
			commands.add("-Xss" + stacksize);
			commands.add("-XX:MaxPermSize=" + permsize);
//			boolean debug = true;
//			if (debug ) {
//				command += " -Xdebug -Xrunjdwp:transport=dt_socket,address=8998,server=y";
//			}
			String cp = "";
			boolean escapeCompletePath = isMac;
//			if (escapeCompletePath) {
//				// OSX fucks up with single jar files escaped, so we try to escape the whole thing
//				command += "\"";
//			}
			cp += "lib" + File.pathSeparator;
			File dir = new File(destDir + File.separator + "lib");
			for (File lib : dir.listFiles()) {
				if (lib.isFile()) {
					if (lib.getName().contains(" ") && !escapeCompletePath) {
						cp += "\"lib" + File.separator + lib.getName() + "\"" + File.pathSeparator;
					} else {
						cp += "lib" + File.separator + lib.getName() + File.pathSeparator;
					}
				}
			}
			if (cp.endsWith(File.pathSeparator)) {
				cp = cp.substring(0, cp.length()-1);
			}
			commands.add("-classpath");
			commands.add(cp);
//			if (escapeCompletePath) {
//				// OSX fucks up with single jar files escaped, so we try to escape the whole thing
//				command += "\"";
//			}
			Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
			String realMainClass = "";
			while (resources.hasMoreElements()) {
				URL url = resources.nextElement();
				Manifest manifest = new Manifest(url.openStream());
				Attributes mainAttributes = manifest.getMainAttributes();
				for (Object key : mainAttributes.keySet()) {
					if (key.toString().equals("Real-Main-Class")) {
						realMainClass = mainAttributes.get(key).toString();
						break;
					}
				}
			}
			System.out.println("Main class: " + realMainClass);
			commands.add(realMainClass);
			commands.add("address=" + address);
			commands.add("port=" + port);
			commands.add("homedir=" + homedir);
			
			System.out.println("\nCommands:");
			for (String command : commands) {
				System.out.println(command);
			}
			ProcessBuilder processBuilder = new ProcessBuilder(commands);
			processBuilder.directory(destDir);
			
//			System.out.println("Running: " + command);
			exec = processBuilder.start();
//			exec = Runtime.getRuntime().exec(command, null, destDir);

			new Thread(new Runnable(){
				@Override
				public void run() {
					BufferedInputStream inputStream = new BufferedInputStream(exec.getInputStream());
					byte[] buffer = new byte[1024];
					int red;
					try {
						red = inputStream.read(buffer);
						while (red != -1) {
							String s = new String(buffer, 0, red);
							System.out.print(s);
							red = inputStream.read(buffer);
						}
					} catch (IOException e) {
					}
				}}, "Sysin reader").start();
			new Thread(new Runnable(){
				@Override
				public void run() {
					BufferedInputStream errorStream = new BufferedInputStream(exec.getErrorStream());
					byte[] buffer = new byte[1024];
					int red;
					try {
						red = errorStream.read(buffer);
						while (red != -1) {
							String s = new String(buffer, 0, red);
							System.out.print(s);
							red = errorStream.read(buffer);
						}
					} catch (IOException e) {
					}
				}}, "Syserr reader").start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private File expand() {
		JarFile jar = null;
		String jarFileName = getJarFileNameNew();
		File destDir = new File(jarFileName.substring(0, jarFileName.indexOf(".jar")));
		if (!destDir.isDirectory()) {
			System.out.println("Expanding " + jarFileName);
			try {
				jar = new java.util.jar.JarFile(jarFileName);
				Enumeration<JarEntry> enumr = jar.entries();
				while (enumr.hasMoreElements()) {
					JarEntry file = enumr.nextElement();
					System.out.println(file.getName());
					File f = new File(destDir, file.getName());
					if (file.isDirectory()) {
						if (!f.getParentFile().exists()) {
							f.getParentFile().mkdir();
						}
						f.mkdir();
						continue;
					}
					InputStream is = jar.getInputStream(file);
					FileOutputStream fos = new FileOutputStream(f);
					byte[] buffer = new byte[1024];
					int red = is.read(buffer);
					while (red != -1) {
						fos.write(buffer, 0, red);
						red = is.read(buffer);
					}
					fos.close();
					is.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					if (jar != null) {
						jar.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		} else {
			System.out.println("No expanding necessary");
		}
		return destDir;
	}

	private String getJarFileNameNew() {
		String name = this.getClass().getName().replace(".", "/") + ".class";
		URL urlJar = getClass().getClassLoader().getResource(name);
		String urlString = urlJar.getFile();
		urlString = urlString.substring(urlString.indexOf(":") + 1, urlString.indexOf("!"));
		try {
			return URLDecoder.decode(urlString, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}
}
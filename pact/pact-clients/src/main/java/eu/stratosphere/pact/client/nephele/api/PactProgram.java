/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.client.nephele.api;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.stratosphere.pact.common.plan.Plan;
import eu.stratosphere.pact.common.plan.PlanAssembler;
import eu.stratosphere.pact.common.plan.PlanAssemblerDescription;
import eu.stratosphere.pact.compiler.PactCompiler;
import eu.stratosphere.pact.compiler.plan.DataSinkNode;

/**
 * This class encapsulates most of the plan related functions. Based on the given jar file,
 * pact assembler class and arguments it can create the plans necessary for execution, or
 * also provide a description of the plan if available.
 */
public class PactProgram {

	/**
	 * Property name of the pact assembler definition in the JAR manifest file.
	 */
	public static final String MANIFEST_ATTRIBUTE_ASSEMBLER_CLASS = "Pact-Assembler-Class";
	
	private static final Pattern BREAK_TAGS = Pattern.compile("<(b|B)(r|R) */?>");
	private static final Pattern REMOVE_TAGS = Pattern.compile("<.+?>");

	// --------------------------------------------------------------------------------------------

	private final Class<? extends PlanAssembler> assemblerClass;

	private final File jarFile;

	private final String[] args;
	
	private List<File> extractedTempLibraries;
	
	private Plan plan;

	/**
	 * Creates an instance that wraps the plan defined in the jar file using the given
	 * argument.
	 * 
	 * @param jarFile
	 *        The jar file which contains the plan and a Manifest which defines
	 *        the Pact-Assembler-Class
	 * @param args
	 *        Optional. The arguments used to create the pact plan, depend on
	 *        implementation of the pact plan. See getDescription().
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 */
	public PactProgram(File jarFile, String... args) throws ProgramInvocationException {
		this.jarFile = jarFile;
		this.args = args == null ? new String[0] : args;
		this.assemblerClass = getPactAssemblerFromJar(jarFile);
	}

	/**
	 * Creates an instance that wraps the plan defined in the jar file using the given
	 * arguments. For generating the plan the class defined in the className parameter
	 * is used.
	 * 
	 * @param jarFile
	 *        The jar file which contains the plan.
	 * @param className
	 *        Name of the class which generates the plan. Overrides the class defined
	 *        in the jar file manifest
	 * @param args
	 *        Optional. The arguments used to create the pact plan, depend on
	 *        implementation of the pact plan. See getDescription().
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 */
	public PactProgram(File jarFile, String className, String... args) throws ProgramInvocationException {
		this.jarFile = jarFile;
		this.args = args == null ? new String[0] : args;
		this.assemblerClass = getPactAssemblerFromJar(jarFile, className);
	}
	
	/**
	 * Returns the plan with all required jars.
	 * @throws IOException 
	 * @throws ErrorInPlanAssemblerException 
	 * @throws ProgramInvocationException 
	 */
	public PlanWithJars getPlanWithJars() throws ProgramInvocationException, ErrorInPlanAssemblerException, IOException {
		List<String> result = new ArrayList<String>();
		for (File jar: extractContainedLibaries(jarFile)) {
			result.add(jar.getAbsolutePath());
		}
		result.add(jarFile.getAbsolutePath());
		return new PlanWithJars(getPlan(), result);
	}

	/**
	 * Returns the plan as generated from the Pact Assembler.
	 * 
	 * @return
	 *         the generated plan
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 * @throws ErrorInPlanAssemblerException
	 *         Thrown if an error occurred in the user-provided pact assembler. This may indicate
	 *         missing parameters for generation.
	 */
	private Plan getPlan() throws ProgramInvocationException, ErrorInPlanAssemblerException {
		if (this.plan == null) {
		}
		this.plan = createPlanFromJar(this.assemblerClass, this.args);
		return this.plan;
	}

	/**
	 * Returns the analyzed plan without any optimizations.
	 * 
	 * @return
	 *         the analyzed plan without any optimizations.
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 * @throws ErrorInPlanAssemblerException
	 *         Thrown if an error occurred in the user-provided pact assembler. This may indicate
	 *         missing parameters for generation.
	 */
	public List<DataSinkNode> getPreviewPlan() throws ProgramInvocationException, ErrorInPlanAssemblerException {
		Plan plan = getPlan();
		if (plan != null) {
			return PactCompiler.createPreOptimizedPlan(plan);
		} else {
			return null;
		}
	}

	/**
	 * Returns the description provided by the PlanAssembler class. This
	 * may contain a description of the plan itself and its arguments.
	 * 
	 * @return The description of the PactProgram's input parameters.
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 * @throws ErrorInPlanAssemblerException
	 *         Thrown if an error occurred in the user-provided pact assembler. This may indicate
	 *         missing parameters for generation.
	 */
	public String getDescription() throws ProgramInvocationException {
		PlanAssembler assembler = instantiateAssemblerFromClass(this.assemblerClass);
		if (assembler instanceof PlanAssemblerDescription) {
			return ((PlanAssemblerDescription) assembler).getDescription();
		} else {
			return null;
		}
	}

	/**
	 * Returns the description provided by the PlanAssembler class without
	 * any HTML tags. This may contain a description of the plan itself
	 * and its arguments.
	 * 
	 * @return The description of the PactProgram's input parameters without HTML mark-up.
	 * @throws ProgramInvocationException
	 *         This invocation is thrown if the PlanAssembler can't be properly loaded. Causes
	 *         may be a missing / wrong class or manifest files.
	 * @throws ErrorInPlanAssemblerException
	 *         Thrown if an error occurred in the user-provided pact assembler. This may indicate
	 *         missing parameters for generation.
	 */
	public String getTextDescription() throws ProgramInvocationException {
		String descr = getDescription();
		if (descr == null || descr.length() == 0) {
			return null;
		} else {
			
			Matcher m = BREAK_TAGS.matcher(descr);
			descr = m.replaceAll("\n");
			m = REMOVE_TAGS.matcher(descr);
			// TODO: Properly convert &amp; etc
			return m.replaceAll("");
		}
	}

	/**
	 * Takes all JAR files that are contained in this program's JAR file and extracts them
	 * to the system's temp directory.
	 * 
	 * @return The file names of the extracted temporary files.
	 * @throws IOException Thrown, if the extraction process failed.
	 */
	public List<File> extractContainedLibaries(File jarFile) throws IOException {
		if (this.extractedTempLibraries != null) {
			return this.extractedTempLibraries;
			
		}
		Random rnd = new Random();
		
		try {
			final JarFile jar = new JarFile(jarFile);
			final List<JarEntry> containedJarFileEntries = new ArrayList<JarEntry>();
			
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				
				if (name.length() > 8 && 
						name.startsWith("lib/") && name.endsWith(".jar"))
				{
					containedJarFileEntries.add(entry);
				}
			}
			
			if (containedJarFileEntries.isEmpty()) {
				this.extractedTempLibraries = Collections.emptyList();
				return this.extractedTempLibraries;
			}
			
			// go over all contained jar files
			this.extractedTempLibraries = new ArrayList<File>(containedJarFileEntries.size());
			for (int i = 0; i < this.extractedTempLibraries.size(); i++)
			{
				final JarEntry entry = containedJarFileEntries.get(i);
				String name = entry.getName();
				name = name.replace(File.separatorChar, '_');
				
				File tempFile = File.createTempFile(String.valueOf(Math.abs(rnd.nextInt()) + "_"), name);
				this.extractedTempLibraries.set(i, tempFile);
			
				// copy the temp file contents to a temporary File
				OutputStream out = null;
				InputStream in = null; 
				try {
					out = new FileOutputStream(tempFile);
					in = new BufferedInputStream(jar.getInputStream(entry));
					byte[] buffer = new byte[1024];
					int numRead = 0;
					while ((numRead = in.read(buffer)) != -1) {
						out.write(buffer, 0, numRead);
					}
				}
				finally {
					if (out != null) {
						out.close();
					}
					if (in != null) {
						in.close();
					}
				}
			}
			
			return this.extractedTempLibraries;
		}
		catch (IOException ioex) {
			throw ioex;
		}
		catch (Throwable t) {
			throw new IOException("Unknown I/O error while extracting contained jar files.", t);
		}
	}
	
	/**
	 * Deletes all temporary files created for contained packaged libraries.
	 */
	public void deleteExtractedLibraries() {
		if (this.extractedTempLibraries != null) {
			for (int i = 0; i < this.extractedTempLibraries.size(); i++) {
				this.extractedTempLibraries.get(i).delete();
				this.extractedTempLibraries.set(i, null);
			}
			this.extractedTempLibraries = null;
		}
	}

	/**
	 * Takes the jar described by the given file and invokes its pact assembler class to
	 * assemble a plan. The assembler class name is either passed through a parameter,
	 * or it is read from the manifest of the jar. The assembler is handed the given options
	 * for its assembly.
	 * 
	 * @param clazz
	 *        The name of the assembler class, or null, if the class should be read from
	 *        the manifest.
	 * @param options
	 *        The options for the assembler.
	 * @return The plan created by the assembler.
	 * @throws ProgramInvocationException
	 *         Thrown, if the jar file or its manifest could not be accessed, or if the assembler
	 *         class was not found or could not be instantiated.
	 * @throws ErrorInPlanAssemblerException
	 *         Thrown, if an error occurred in the user-provided pact assembler.
	 */
	protected static Plan createPlanFromJar(Class<? extends PlanAssembler> clazz, String[] options)
			throws ProgramInvocationException, ErrorInPlanAssemblerException
	{
		PlanAssembler assembler = instantiateAssemblerFromClass(clazz);
		try {
			return assembler.getPlan(options);
		} catch (Throwable t) {
			throw new ErrorInPlanAssemblerException("Error while creating plan: " + t, t);
		}
	}

	/**
	 * Instantiates the given plan assembler class
	 * 
	 * @param clazz
	 *        class that should be instantiated.
	 * @return
	 *         instance of the class
	 * @throws ProgramInvocationException
	 *         is thrown if class can't be found or instantiated
	 */
	protected static PlanAssembler instantiateAssemblerFromClass(Class<? extends PlanAssembler> clazz)
			throws ProgramInvocationException
	{
		try {
			return clazz.newInstance();
		} catch (InstantiationException e) {
			throw new ProgramInvocationException("ERROR: The pact plan assembler class could not be instantiated. "
				+ "Make sure that the class is a proper class (not abstract/interface) and has a "
				+ "public constructor with no arguments.", e);
		} catch (IllegalAccessException e) {
			throw new ProgramInvocationException("ERROR: The pact plan assembler class could not be instantiated. "
				+ "Make sure that the class has a public constructor with no arguments.", e);
		} catch (Throwable t) {
			throw new ProgramInvocationException("An error ocurred during the instantiation of the "
				+ "program assembler: " + t.getMessage(), t);
		}
	}

	private Class<? extends PlanAssembler> getPactAssemblerFromJar(File jarFile)
			throws ProgramInvocationException
	{
		JarFile jar = null;
		Manifest manifest = null;
		String className = null;

		// Open jar file
		try {
			PlanWithJars.checkJarFile(jarFile);
			jar = new JarFile(jarFile);
		} catch (IOException ioex) {
			throw new ProgramInvocationException("Error while opening jar file '" + jarFile.getPath() + "'. "
				+ ioex.getMessage(), ioex);
		}

		// Read from jar manifest
		try {
			manifest = jar.getManifest();
		} catch (IOException ioex) {
			throw new ProgramInvocationException("The Manifest in the JAR file could not be accessed '"
				+ jarFile.getPath() + "'. " + ioex.getMessage(), ioex);
		}

		if (manifest == null) {
			throw new ProgramInvocationException("No manifest found in jar '" + jarFile.getPath() + "'");
		}

		Attributes attributes = manifest.getMainAttributes();
		className = attributes.getValue(PactProgram.MANIFEST_ATTRIBUTE_ASSEMBLER_CLASS);

		if (className == null) {
			throw new ProgramInvocationException("No plan assembler class defined in manifest");
		}

		try {
			jar.close();
		} catch (IOException ex) {
			throw new ProgramInvocationException("Could not close JAR. " + ex.getMessage(), ex);
		}

		return getPactAssemblerFromJar(jarFile, className);
	}

	private Class<? extends PlanAssembler> getPactAssemblerFromJar(File jarFile, String className)
			throws ProgramInvocationException
	{
		Class<? extends PlanAssembler> clazz = null;


		try {
			PlanWithJars.checkJarFile(jarFile);
			List<File> nestedJars = extractContainedLibaries(jarFile);
			
			URL[] urls = new URL[1 + nestedJars.size()];
			urls[0] = jarFile.getAbsoluteFile().toURI().toURL();
			
			// add the nested jars
			for (int i = 0; i < nestedJars.size(); i++) {
				urls[i+1] = nestedJars.get(i).getAbsoluteFile().toURI().toURL();
			}
			
			ClassLoader loader = new URLClassLoader(urls, this.getClass().getClassLoader());
			clazz = Class.forName(className, true, loader).asSubclass(PlanAssembler.class);
		}
		catch (MalformedURLException e) {
			throw new ProgramInvocationException(
				"The given JAR file could not be translated to a valid URL for class access.", e);
		}
		catch (ClassNotFoundException e) {
			throw new ProgramInvocationException("The pact plan assembler class '" + className
				+ "' was not found in the jar file '" + jarFile.getPath() + "'.", e);
		}
		catch (ClassCastException e) {
			throw new ProgramInvocationException("The pact plan assembler class '" + className
				+ "' cannot be cast to PlanAssembler.", e);
		}
		catch (IOException ioex) {
			throw new ProgramInvocationException("The jar file could not be checked for nested jar files: " +
				ioex.getMessage(), ioex);
		}
		catch (Throwable t) {
			throw new ProgramInvocationException("An unknown problem ocurred during the instantiation of the "
				+ "program assembler: " + t, t);
		}

		return clazz;
	}

}
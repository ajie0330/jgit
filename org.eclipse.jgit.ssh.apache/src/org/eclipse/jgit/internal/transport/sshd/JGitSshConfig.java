/*
 * Copyright (C) 2018, Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.transport.sshd;

import static org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile.flag;
import static org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile.positive;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.TreeMap;

import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile;
import org.eclipse.jgit.internal.transport.ssh.OpenSshConfigFile.HostEntry;
import org.eclipse.jgit.transport.SshConstants;

/**
 * A {@link HostConfigEntryResolver} adapted specifically for JGit.
 * <p>
 * We use our own config file parser and entry resolution since the default
 * {@link org.apache.sshd.client.config.hosts.ConfigFileHostEntryResolver
 * ConfigFileHostEntryResolver} has a number of problems:
 * </p>
 * <ul>
 * <li>It does case-insensitive pattern matching. Matching in OpenSsh is
 * case-sensitive! Compare also bug 531118.</li>
 * <li>It only merges values from the global items (before the first "Host"
 * line) into the host entries. Otherwise it selects the most specific match.
 * OpenSsh processes <em>all</em> entries in the order they appear in the file
 * and whenever one matches, it updates values as appropriate.</li>
 * <li>We have to ensure that ~ replacement uses the same HOME directory as
 * JGit. Compare bug bug 526175.</li>
 * </ul>
 * Therefore, this re-uses the parsing and caching from
 * {@link OpenSshConfigFile}.
 *
 */
public class JGitSshConfig implements HostConfigEntryResolver {

	private final OpenSshConfigFile configFile;

	private final String localUserName;

	/**
	 * Creates a new {@link OpenSshConfigFile} that will read the config from
	 * file {@code config} use the given file {@code home} as "home" directory.
	 *
	 * @param home
	 *            user's home directory for the purpose of ~ replacement
	 * @param config
	 *            file to load; may be {@code null} if no ssh config file
	 *            handling is desired
	 * @param localUserName
	 *            user name of the current user on the local host OS
	 */
	public JGitSshConfig(@NonNull File home, File config,
			@NonNull String localUserName) {
		this.localUserName = localUserName;
		configFile = config == null ?  null : new OpenSshConfigFile(home, config, localUserName);
	}

	@Override
	public HostConfigEntry resolveEffectiveHost(String host, int port,
			SocketAddress localAddress, String username,
			AttributeRepository attributes) throws IOException {
		HostEntry entry = configFile == null ? new HostEntry() : configFile.lookup(host, port, username);
		JGitHostConfigEntry config = new JGitHostConfigEntry();
		// Apache MINA conflates all keys, even multi-valued ones, in one map
		// and puts multiple values separated by commas in one string. See
		// the javadoc on HostConfigEntry.
		Map<String, String> allOptions = new TreeMap<>(
				String.CASE_INSENSITIVE_ORDER);
		allOptions.putAll(entry.getOptions());
		// And what if a value contains a comma??
		entry.getMultiValuedOptions().entrySet().stream()
				.forEach(e -> allOptions.put(e.getKey(),
						String.join(",", e.getValue()))); //$NON-NLS-1$
		config.setProperties(allOptions);
		// The following is an extension from JGitHostConfigEntry
		config.setMultiValuedOptions(entry.getMultiValuedOptions());
		// Also make sure the underlying properties are set
		String hostName = entry.getValue(SshConstants.HOST_NAME);
		if (hostName == null || hostName.isEmpty()) {
			hostName = host;
		}
		config.setHostName(hostName);
		config.setProperty(SshConstants.HOST_NAME, hostName);
		config.setHost(SshdSocketAddress.isIPv6Address(hostName) ? "" : hostName); //$NON-NLS-1$
		String user = username != null && !username.isEmpty() ? username
				: entry.getValue(SshConstants.USER);
		if (user == null || user.isEmpty()) {
			user = localUserName;
		}
		config.setUsername(user);
		config.setProperty(SshConstants.USER, user);
		int p = port >= 0 ? port : positive(entry.getValue(SshConstants.PORT));
		config.setPort(p >= 0 ? p : SshConstants.SSH_DEFAULT_PORT);
		config.setProperty(SshConstants.PORT,
				Integer.toString(config.getPort()));
		config.setIdentities(entry.getValues(SshConstants.IDENTITY_FILE));
		config.setIdentitiesOnly(
				flag(entry.getValue(SshConstants.IDENTITIES_ONLY)));
		return config;
	}

}

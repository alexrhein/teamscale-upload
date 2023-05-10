package com.teamscale.upload.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.jetbrains.nativecerts.NativeTrustedCertificates;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

/**
 * Utilities for creating an {@link OkHttpClient}
 */
public class OkHttpUtils {

	/**
	 * An empty request body that can be reused.
	 */
	public static final RequestBody EMPTY_BODY = RequestBody.create(null, new byte[0]);

	/**
	 * Creates the {@link OkHttpClient} based on the given connection settings.
	 *
	 * @param trustStorePath
	 *            May be null if no trust store should be used.
	 * @param trustStorePassword
	 *            May be null if no trust store should be used.
	 */
	public static OkHttpClient createClient(boolean validateSsl, String trustStorePath, String trustStorePassword,
											long timeoutInSeconds) {
		OkHttpClient.Builder builder = new OkHttpClient.Builder();

		setTimeouts(builder, timeoutInSeconds);
		builder.followRedirects(false).followSslRedirects(false);

		configureTrustStore(builder, trustStorePath, trustStorePassword);
		if (!validateSsl) {
			disableSslValidation(builder);
		}

		return builder.build();
	}

	/**
	 * Reads the keystore at the given path and configures the builder so the
	 * {@link OkHttpClient} will accept the certificates stored in the keystore.
	 */
	private static void configureTrustStore(OkHttpClient.Builder builder, String trustStorePath,
											String trustStorePassword) {

		KeyStore keyStore = getCustomKeyStore(trustStorePath, trustStorePassword);

		try {
			SSLContext sslContext = SSLContext.getInstance("SSL");
			TrustManagerFactory trustManagerFactory = TrustManagerFactory
					.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trustManagerFactory.init(keyStore);

			List<TrustManager> trustManagers = new ArrayList<>(List.of(trustManagerFactory.getTrustManagers()));

			if (trustManagers.size() == 0) {
				LogUtils.fail("No custom trust managers found. This is a bug. Please report it to CQSE.");
			}

			// Add the trust manager of the JVM
			trustManagers.addAll(getDefaultTrustManagers());

			MultiTrustManager multiTrustManager = new MultiTrustManager(trustManagers);

			sslContext.init(null, new TrustManager[]{multiTrustManager}, new SecureRandom());
			builder.sslSocketFactory(sslContext.getSocketFactory(), multiTrustManager);
		} catch (NoSuchAlgorithmException e) {
			LogUtils.failWithStackTrace("Failed to instantiate an SSLContext or TrustManagerFactory."
					+ "\nThis is a bug. Please report it to CQSE.", e);
		} catch (KeyStoreException e) {
			LogUtils.failWithStackTrace("Failed to initialize the TrustManagerFactory with the keystore."
					+ "\nThis is a bug. Please report it to CQSE.", e);
		} catch (KeyManagementException e) {
			LogUtils.failWithStackTrace("Failed to initialize the SSLContext with the trust managers."
					+ "\nThis is a bug. Please report it to CQSE.", e);
		} catch (ClassCastException e) {
			LogUtils.failWithStackTrace(
					"Trust manager is not of X509 format." + "\nThis is a bug. Please report it to CQSE.", e);
		}
	}

	/**
	 * Returns the {@link TrustManager trust managers} of the JVM.
	 */
	private static List<TrustManager> getDefaultTrustManagers() throws NoSuchAlgorithmException, KeyStoreException {
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init((KeyStore) null);

		return List.of(factory.getTrustManagers());
	}

	private static KeyStore getCustomKeyStore(String keystorePath, String keystorePassword) {
		try  {
			KeyStore keyStore;
			if (keystorePath != null) {
				try (FileInputStream stream = new FileInputStream(keystorePath)) {
					keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
					keyStore.load(stream, keystorePassword.toCharArray());
				}
			} else {
				// Create an empty keystore
				keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
				keyStore.load(null);
			}
			addOsSpecificCertificates(keyStore);

			return keyStore;
		} catch (IOException e) {
			LogUtils.failWithoutStackTrace("Failed to read keystore file " + keystorePath
					+ "\nPlease make sure that file exists and is readable and that you provided the correct password."
					+ " Please also make sure that the keystore file is a valid Java keystore."
					+ " You can use the program `keytool` from your JVM installation to check this:"
					+ "\nkeytool -list -keystore " + keystorePath, e);
		} catch (CertificateException e) {
			LogUtils.failWithoutStackTrace("Failed to load one of the certificates in the keystore file " + keystorePath
							+ "\nPlease make sure that the certificate is stored correctly and the certificate version and encoding are supported.",
					e);
		} catch (NoSuchAlgorithmException e) {
			LogUtils.failWithoutStackTrace("Failed to verify the integrity of the keystore file " + keystorePath
							+ " because it uses an unsupported hashing algorithm."
							+ "\nPlease change the keystore so it uses a supported algorithm (e.g. the default used by `keytool` is supported).",
					e);
		} catch (KeyStoreException e) {
			LogUtils.failWithStackTrace(
					"Failed to instantiate an in-memory keystore." + "\nThis is a bug. Please report it to CQSE.", e);
		}

		return null;
	}

	/**
	 * Adds the {@link NativeTrustedCertificates#getCustomOsSpecificTrustedCertificates() OS trusted certificates}
	 * to the given {@link KeyStore}
	 */
	private static void addOsSpecificCertificates(KeyStore keyStore) throws KeyStoreException {
		Collection<X509Certificate> osCertificates = NativeTrustedCertificates.getCustomOsSpecificTrustedCertificates();
		LogUtils.debug(String.format("Imported %s certificates from the operating system", osCertificates.size()));
		for (X509Certificate certificate : osCertificates) {
			keyStore.setCertificateEntry(certificate.getSubjectX500Principal().getName(), certificate);
		}
	}

	private static void disableSslValidation(OkHttpClient.Builder builder) {
		SSLSocketFactory sslSocketFactory;
		try {
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[] { TrustAllCertificatesManager.INSTANCE }, new SecureRandom());
			sslSocketFactory = sslContext.getSocketFactory();
		} catch (GeneralSecurityException e) {
			LogUtils.warn("Could not disable SSL certificate validation. Leaving it enabled", e);
			return;
		}

		builder.sslSocketFactory(sslSocketFactory, TrustAllCertificatesManager.INSTANCE);
		builder.hostnameVerifier((hostName, session) -> true);
	}

	private static void setTimeouts(okhttp3.OkHttpClient.Builder builder, long timeoutInSeconds) {
		builder.connectTimeout(timeoutInSeconds, TimeUnit.SECONDS);
		builder.readTimeout(timeoutInSeconds, TimeUnit.SECONDS);
		builder.writeTimeout(timeoutInSeconds, TimeUnit.SECONDS);
	}

	private static class TrustAllCertificatesManager implements X509TrustManager {
		static final TrustAllCertificatesManager INSTANCE = new TrustAllCertificatesManager();

		public TrustAllCertificatesManager() {
		}

		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		public void checkServerTrusted(X509Certificate[] certs, String authType) {
		}

		public void checkClientTrusted(X509Certificate[] certs, String authType) {
		}
	}

	/**
	 * Combines multiple {@link X509TrustManager}.
	 * If one of the managers trust the certificate chain, the {@link MultiTrustManager} will trust the certificate.
	 */
	private static class MultiTrustManager implements X509TrustManager {

		private final List<X509TrustManager> trustManagers;

		private MultiTrustManager(List<TrustManager> managers) {
			trustManagers = managers.stream().map(manager -> (X509TrustManager) manager).collect(Collectors.toList());
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return trustManagers.stream().flatMap(manager -> Arrays.stream(manager.getAcceptedIssuers())).toArray(X509Certificate[]::new);
		}


		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			checkAll(manager -> manager.checkClientTrusted(chain, authType));
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			checkAll(manager -> manager.checkServerTrusted(chain, authType));
		}

		private void checkAll(ConsumerWithException<X509TrustManager, CertificateException> check) throws CertificateException {
			Collection<CertificateException> exceptions = new ArrayList<>();

			for (int i = 0; i < trustManagers.size(); i++) {
				try {
					check.accept(trustManagers.get(i));
					// We have found one manager which trusts the certificate
					return;
				} catch (CertificateException e) {
					if (i == trustManagers.size() - 1) {
						// No manager trusts the certificate
						exceptions.forEach(e::addSuppressed);
						throw e;
					} else {
						exceptions.add(e);
					}
				}
			}
		}
	}

	private interface ConsumerWithException<T, E extends Exception> {

		void accept(T t) throws E;
	}
}

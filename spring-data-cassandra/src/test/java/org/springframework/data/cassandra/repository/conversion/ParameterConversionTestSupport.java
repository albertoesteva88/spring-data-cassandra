/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.repository.conversion;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.cassandra.config.CassandraSessionFactoryBean;
import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.core.CassandraAdminOperations;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.convert.CassandraCustomConversions;
import org.springframework.data.cassandra.core.cql.CqlIdentifier;
import org.springframework.data.cassandra.core.mapping.SimpleUserTypeResolver;
import org.springframework.data.cassandra.core.mapping.UserTypeResolver;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;
import org.springframework.data.cassandra.repository.support.AbstractSpringDataEmbeddedCassandraIntegrationTest;
import org.springframework.data.cassandra.repository.support.IntegrationTestConfig;
import org.springframework.data.convert.CustomConversions;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test support for query method parameter type conversion.
 *
 * @author Mark Paluch
 */
abstract class ParameterConversionTestSupport extends AbstractSpringDataEmbeddedCassandraIntegrationTest {

	@Configuration
	@EnableCassandraRepositories(considerNestedRepositories = true)
	public static class Config extends IntegrationTestConfig {

		@Override
		public String[] getEntityBasePackages() {
			return new String[] { Contact.class.getPackage().getName() };
		}

		@Override
		public SchemaAction getSchemaAction() {
			return SchemaAction.RECREATE_DROP_UNUSED;
		}

		@Override
		public CassandraSessionFactoryBean session() {

			Cluster cluster = cluster().getObject();
			Session session = cluster.connect(getKeyspaceName());
			session.execute("CREATE TYPE IF NOT EXISTS phone (number text);");
			session.close();

			return super.session();
		}

		@Override
		public CustomConversions customConversions() {
			return new CassandraCustomConversions(
					Arrays.asList(AddressReadConverter.INSTANCE, AddressWriteConverter.INSTANCE, PhoneReadConverter.INSTANCE,
							new PhoneWriteConverter(new SimpleUserTypeResolver(cluster().getObject(), getKeyspaceName()))));
		}
	}

	@Autowired CassandraOperations template;
	@Autowired CassandraAdminOperations adminOperations;

	Contact walter, flynn;

	@Before
	public void before() {

		deleteAllEntities();

		template.getCqlOperations().execute("CREATE INDEX IF NOT EXISTS contact_address ON contact (address);");
		template.getCqlOperations().execute("CREATE INDEX IF NOT EXISTS contact_addresses ON contact (addresses);");

		template.getCqlOperations().execute("CREATE INDEX IF NOT EXISTS contact_main_phones ON contact (mainphone);");
		template.getCqlOperations()
				.execute("CREATE INDEX IF NOT EXISTS contact_alternative_phones ON contact (alternativephones);");

		walter = new Contact("Walter");
		walter.setAddress(new Address("Albuquerque", "USA"));
		walter.setAddresses(Arrays.asList(new Address("Albuquerque", "USA"), new Address("New Hampshire", "USA"),
				new Address("Grocery Store", "Mexico")));

		Phone phone = new Phone();
		phone.setNumber("(505) 555-1258");

		Phone alternative = new Phone();
		alternative.setNumber("505-842-4205");

		walter.setMainPhone(phone);
		walter.setAlternativePhones(Collections.singletonList(alternative));

		flynn = new Contact("Flynn");
		flynn.setAddress(new Address("Albuquerque", "USA"));
		flynn.setAddresses(Collections.singletonList(new Address("Albuquerque", "USA")));

		template.insert(walter);
		template.insert(flynn);
	}

	/**
	 * @author Mark Paluch
	 */
	enum AddressWriteConverter implements Converter<Address, String> {
		INSTANCE;

		public String convert(Address source) {

			try {
				return new ObjectMapper().writeValueAsString(source);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * @author Mark Paluch
	 */
	private enum PhoneReadConverter implements Converter<UDTValue, Phone> {

		INSTANCE;

		public Phone convert(UDTValue source) {

			Phone phone = new Phone();
			phone.setNumber(source.getString("number"));

			return phone;
		}
	}

	/**
	 * @author Mark Paluch
	 */
	private static class PhoneWriteConverter implements Converter<Phone, UDTValue> {

		private UserTypeResolver userTypeResolver;

		PhoneWriteConverter(UserTypeResolver userTypeResolver) {
			this.userTypeResolver = userTypeResolver;
		}

		public UDTValue convert(Phone source) {

			UserType userType = userTypeResolver.resolveType(CqlIdentifier.of("phone"));
			UDTValue udtValue = userType.newValue();
			udtValue.setString("number", source.getNumber());

			return udtValue;
		}
	}

	/**
	 * @author Mark Paluch
	 */
	private enum AddressReadConverter implements Converter<String, Address> {

		INSTANCE;

		public Address convert(String source) {

			if (StringUtils.hasText(source)) {
				try {
					return new ObjectMapper().readValue(source, Address.class);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}

			return null;
		}
	}
}

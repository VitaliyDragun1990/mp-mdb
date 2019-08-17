package org.myphotos.mdb.ejb.bean;

import static javax.ejb.TransactionAttributeType.NOT_SUPPORTED;
import static org.myphotos.config.JMSEnvironmentSettings.JMS_CONNECTION_FACTORY_JNDI_NAME;
import static org.myphotos.config.JMSEnvironmentSettings.UPLOAD_REQUEST_QUEUE_JNDI_NAME;
import static org.myphotos.config.JMSEnvironmentSettings.UPLOAD_RESPONSE_QUEUE_JNDI_NAME;
import static org.myphotos.config.JMSMessageProperty.IMAGE_RESOURCE_TEMP_PATH;
import static org.myphotos.config.JMSMessageProperty.IMAGE_RESOURCE_TYPE;
import static org.myphotos.config.JMSMessageProperty.PROFILE_ID;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Resource;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.inject.Inject;
import javax.jms.DeliveryMode;
import javax.jms.JMSConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSSessionMode;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Queue;

import org.myphotos.config.JMSImageResourceType;
import org.myphotos.config.JMSMessageProperty;
import org.myphotos.domain.entity.Photo;
import org.myphotos.domain.entity.Profile;
import org.myphotos.domain.model.ImageResource;
import org.myphotos.media.ImageUploader;
import org.myphotos.media.model.TempFileFactory;

@MessageDriven(activationConfig = {
		@ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
		@ActivationConfigProperty(propertyName = "destinationLookup",propertyValue = UPLOAD_REQUEST_QUEUE_JNDI_NAME)
})
@TransactionAttribute(NOT_SUPPORTED)
public class UploadRequestProcessorMDBean implements MessageListener {
	
	@Inject
	private Logger logger;
	
	@Inject
	@JMSConnectionFactory(JMS_CONNECTION_FACTORY_JNDI_NAME)
	@JMSSessionMode(JMSContext.AUTO_ACKNOWLEDGE)
	private JMSContext jmsContext;
	
	@Resource(lookup = UPLOAD_RESPONSE_QUEUE_JNDI_NAME)
	private Queue uploadResponseQueue;
	
	@Inject
	private ImageUploader imageUploader;

	@Override
	public void onMessage(Message jmsMessage) {
		try {
			processMessage(jmsMessage);
		} catch (JMSException | RuntimeException e) {
			logger.log(Level.SEVERE, getClass().getName() + ".onMessage failed: " + e.getMessage(), e);
		}
	}

	private void processMessage(Message jmsMessage) throws JMSException {
		MapMessage mapMessage = (MapMessage) jmsMessage;
		
		String imageResourcePath = mapMessage.getString(IMAGE_RESOURCE_TEMP_PATH.name());
		Long profileId = mapMessage.getLong(PROFILE_ID.name());
		JMSImageResourceType imageResourceType = JMSImageResourceType.valueOf(
				mapMessage.getString(IMAGE_RESOURCE_TYPE.name()));
		
		processMessage(imageResourceType, imageResourcePath, profileId);
	}

	private void processMessage(JMSImageResourceType imageResourceType, String imageResourcePath, Long profileId) {
		try {
			processUploadRequest(imageResourceType, imageResourcePath, profileId);
		} catch (RuntimeException e) {
			logger.log(Level.SEVERE, "Image uploading failed: " + e.getMessage(), e);
			sendUploadFailedResponse(profileId, imageResourceType, imageResourcePath, e);
		}
		
	}

	private void processUploadRequest(JMSImageResourceType imageResourceType, String imageResourcePath,
			Long profileId) {
		Serializable responseEntity = executeUpload(imageResourceType, imageResourcePath, profileId);
		
		sendUploadSucceedResponse(profileId, imageResourceType, imageResourcePath, responseEntity);
	}

	private Serializable executeUpload(JMSImageResourceType imageResourceType, String imageResourcePath,
			Long profileId) {
		Serializable responseEntity;
		if (imageResourceType == JMSImageResourceType.IMAGE_RESOURCE_PHOTO) {
			responseEntity = uploadNewPhoto(imageResourcePath);
		} else {
			responseEntity = uploadNewAvatar(imageResourcePath, profileId);
		}
		return responseEntity;
	}

	private Serializable uploadNewPhoto(String imageResourcePath) {
		Photo photo = imageUploader.uploadPhoto(new TempFileImageResource(imageResourcePath));
		
		logger.log(Level.INFO, "New photo has been successfully uploaded: smallUrl={0}, largeUrl={1}, originalUrl={2}",
				new Object[] {photo.getSmallUrl(), photo.getLargeUrl(), photo.getOriginalUrl()});
		
		return photo;
	}

	private Serializable uploadNewAvatar(String imageResourcePath, Long profileId) {
		Profile profile = new Profile();
		profile.setAvatarUrl(imageUploader.uploadAvatar(new TempFileImageResource(imageResourcePath)));
		
		logger.log(Level.INFO, "New avatar image {0} has been successfully uploaded for profile {1}",
				new Object[] {profile.getAvatarUrl(), profileId});
		
		return profile;
	}

	private void sendUploadSucceedResponse(Long profileId, JMSImageResourceType imageResourceType,
			String imageResourcePath, Serializable responseEntity) {
		jmsContext.createProducer()
				.setDeliveryDelay(DeliveryMode.NON_PERSISTENT)
				.setProperty(JMSMessageProperty.REQUEST_SUCCESS.name(), true)
				.setProperty(JMSMessageProperty.PROFILE_ID.name(), profileId)
				.setProperty(JMSMessageProperty.IMAGE_RESOURCE_TYPE.name(), imageResourceType.name())
				.setProperty(JMSMessageProperty.IMAGE_RESOURCE_TEMP_PATH.name(), imageResourcePath)
				.send(uploadResponseQueue, responseEntity);
	}

	private void sendUploadFailedResponse(Long profileId, JMSImageResourceType imageResourceType,
			String imageResourcePath, RuntimeException exception) {
		jmsContext.createProducer()
			.setDeliveryDelay(DeliveryMode.NON_PERSISTENT)
			.setProperty(JMSMessageProperty.REQUEST_SUCCESS.name(), false)
			.setProperty(JMSMessageProperty.PROFILE_ID.name(), profileId)
			.setProperty(JMSMessageProperty.IMAGE_RESOURCE_TYPE.name(), imageResourceType.name())
			.setProperty(JMSMessageProperty.IMAGE_RESOURCE_TEMP_PATH.name(), imageResourcePath)
			.send(uploadResponseQueue, exception);
	}
	
	private static class TempFileImageResource implements ImageResource {
		
		private final Path tempPath;
		
		public TempFileImageResource(String tempPath) {
			this.tempPath = Paths.get(tempPath);
		}

		@Override
		public void close() throws Exception {
			TempFileFactory.deleteTempFile(tempPath);
			
		}

		@Override
		public Path getPath() {
			return tempPath;
		}
		
	}

}

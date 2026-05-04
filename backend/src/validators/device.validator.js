const Joi = require('joi');

exports.createDeviceSchema = Joi.object({
  ble_name:         Joi.string().max(100).required(),
  ble_mac:          Joi.string().max(50).allow('', null),
  firmware_version: Joi.string().max(50).allow('', null),
});

exports.updateDeviceSchema = Joi.object({
  ble_name:         Joi.string().max(100),
  ble_mac:          Joi.string().max(50).allow('', null),
  firmware_version: Joi.string().max(50).allow('', null),
  device_status:    Joi.string().valid('active', 'inactive', 'removed'),
  last_connected_at:Joi.string().isoDate().allow(null),
}).min(1);

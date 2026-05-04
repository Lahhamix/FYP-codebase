const Joi = require('joi');

const sensorFields = [
  'heart_rate',
  'spo2',
  'bp_systolic',
  'bp_diastolic',
  'swelling_value',
  'step_count',
  'motion_status',
];

exports.createReadingSchema = Joi.object({
  device_id:     Joi.string().uuid().allow(null),
  heart_rate:    Joi.number().integer().min(0).max(300).allow(null),
  spo2:          Joi.number().min(0).max(100).allow(null),
  bp_systolic:   Joi.number().integer().min(0).max(300).allow(null),
  bp_diastolic:  Joi.number().integer().min(0).max(200).allow(null),
  swelling_value:Joi.alternatives()
    .try(Joi.number().min(0), Joi.string().max(80).allow(''))
    .allow(null),
  step_count:    Joi.number().integer().min(0).allow(null),
  motion_status: Joi.string().max(50).allow('', null),
  recorded_at:   Joi.string().isoDate().allow(null),
}).custom((value, helpers) => {
  const hasSensorValue = sensorFields.some((field) => {
    const fieldValue = value[field];
    return fieldValue !== undefined && fieldValue !== null && fieldValue !== '';
  });
  if (!hasSensorValue) return helpers.error('object.emptyReading');
  return value;
}).messages({
  'object.emptyReading': 'At least one sensor value is required.',
});

exports.historyQuerySchema = Joi.object({
  from:  Joi.string().isoDate().required(),
  to:    Joi.string().isoDate().required(),
  limit: Joi.number().integer().min(1).max(1000).default(200),
});

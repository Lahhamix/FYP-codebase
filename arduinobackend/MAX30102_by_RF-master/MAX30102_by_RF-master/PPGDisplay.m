% Set up serial communication using serialport
s = serialport("COM10", 115200, "Timeout", 10);  % Increase the Timeout to 10 seconds for data collection

% Time duration for collecting data (30 seconds to 1 minute)
dataDuration = 60;  % Duration in seconds
startTime = tic;  % Start timer for data collection

% Set up figure for real-time plotting
figure;
h = plot(NaN, NaN, 'r');  % Initialize plot with red color for the red signal
hold on;  % Keep both signals on the same plot
plot(NaN, NaN, 'b');  % Add another plot for the infrared signal (blue)
xlabel('Time (s)');
ylabel('Filtered Signal Amplitude');
title('Real-Time Filtered PPG Signal (Before Peak Detection)');
xlim([0, dataDuration]);  % Adjust x-axis limit based on the data duration
ylim([0, 1.5e5]);  % Adjust y-axis limit based on expected data range

% Buffer for storing data
time_data = [];
red_data = [];
ir_data = [];
time_counter = 1;

% Read and collect data for the specified duration
while toc(startTime) < dataDuration
    % Read one line of filtered data from the Arduino (red and infrared signals)
    data = readline(s);  % Read a line of data (comma-separated)
    
    if ~isempty(data)
        % Check if the data is in the expected format (two comma-separated values)
        data_parts = str2double(strsplit(data, ','));
        if numel(data_parts) == 2
            % Append data to buffer
            time_data = [time_data, time_counter];
            red_data = [red_data, data_parts(1)];
            ir_data = [ir_data, data_parts(2)];

            % Update plot with filtered data
            set(h, 'XData', time_data, 'YData', red_data);  % Plot red signal
            set(findobj(gcf, 'Color', 'b'), 'XData', time_data, 'YData', ir_data);  % Plot infrared signal
            drawnow;
            time_counter = time_counter + 1;
        end
    end
end

% After data collection, display the complete signal
disp('Data collection complete. Displaying the full signal.');

% Plot the final graph showing both red and infrared signals
figure;
plot(time_data, red_data, 'r', 'LineWidth', 1.5);  % Plot the red signal in red
hold on;
plot(time_data, ir_data, 'b', 'LineWidth', 1.5);  % Plot the infrared signal in blue
xlabel('Time (s)');
ylabel('Filtered Signal Amplitude');
title('Filtered PPG Signal (Before Peak Detection)');
xlim([0, dataDuration]);
ylim([0, 1.5e5]);  % Adjust according to the actual data range
legend('Red Signal', 'Infrared Signal');  % Add a legend for clarity

% Close serial communication when done
clear s;